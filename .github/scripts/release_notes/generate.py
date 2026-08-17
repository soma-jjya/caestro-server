#!/usr/bin/env python3
"""
릴리스 노트 초안 생성기.

base..head 구간(예: origin/main..develop)의 커밋/PR/diff를 모아 LLM에게
"다른 팀(mobile/ai/all)이 알아야 할 변경"을 required/fyi로 분류하게 하고,
사람이 검토할 마크다운 초안 + JSON을 만든다.

사용 예 (로컬 백필):
  export ANTHROPIC_API_KEY=...
  python3 generate.py --base 92052fc --head 1c4c9e5            # 릴리스 #57 (하트비트 포함)
  python3 generate.py --base 1c4c9e5 --head 4504bd8            # 릴리스 #65 (#59~#63)
  python3 generate.py --base origin/main --head develop --dry-run   # API 호출 없이 프롬프트만 확인

CI(GitHub Actions)에서는 --base <이전 릴리스 SHA> --head <현재 SHA> 로 같은 스크립트를 쓴다.
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_MODEL = "claude-haiku-4-5-20251001"  # 저렴·충분. 품질 비교는 --model claude-sonnet-5

# 모델별 단가(USD / 1M tokens, 입력·출력). 추정용 — 실제 요금표로 갱신할 것.
PRICES = {
    "claude-sonnet-5": (3.0, 15.0),
    "claude-haiku-4-5-20251001": (1.0, 5.0),
}

# diff 포함 우선순위: 숫자가 작을수록 먼저(계약 → 서버 코드 → 계약성 인프라 → 테스트 → 기타)
PRIORITY_RULES = [
    (0, re.compile(r"/controller/api/[^/]*Api\.java$")),
    (0, re.compile(r"/dto/(request|response)/")),
    (0, re.compile(r"/domain/signaling/dto/")),
    (0, re.compile(r"SignalingWebSocketHandler\.java$")),
    (0, re.compile(r"/error/ErrorCode\.java$")),
    (0, re.compile(r"src/main/resources/application\.yml$")),
    (1, re.compile(r"^src/main/java/")),
    (2, re.compile(r"^lambda/")),
    (2, re.compile(r"^\.github/workflows/")),
    (2, re.compile(r"^(Dockerfile|build\.gradle|settings\.gradle)$")),
    (2, re.compile(r"README\.md$")),
    (3, re.compile(r"^src/test/")),
]
EXCLUDE_RULES = [
    re.compile(r"\.(png|jpg|jpeg|gif|svg|ico|jar|lock)$"),
    re.compile(r"^docs/"),
    re.compile(r"application-(dev|prod|ci)\.yml$"),  # 시크릿 가능성, 릴리스 노트엔 불필요
]

TOOL = {
    "name": "release_notes",
    "description": "이번 릴리스에서 다른 팀이 알아야 할 변경을 구조화한다.",
    "input_schema": {
        "type": "object",
        "properties": {
            "summary": {"type": "string", "description": "이번 릴리스 한두 문장 요약(한국어)"},
            "items": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "audience": {"type": "string", "enum": ["mobile", "ai", "all"]},
                        "tier": {"type": "string", "enum": ["required", "fyi"]},
                        "title": {"type": "string", "description": "한 줄 제목"},
                        "change": {"type": "string", "description": "무엇이 어떻게 바뀌었나"},
                        "required_action": {"type": "string", "description": "required일 때 상대 팀이 해야 할 일"},
                        "recommended_action": {"type": "string",
                                               "description": "fyi지만 새 기능을 활용하려면 권장되는 처리"},
                        "deploy_order_note": {"type": "string", "description": "배포 순서 주의사항(있을 때만)"},
                        "evidence": {"type": "array", "items": {"type": "string"},
                                     "description": "근거 파일 경로 또는 PR 번호"},
                        "confidence": {"type": "string", "enum": ["high", "medium", "low"]},
                    },
                    "required": ["audience", "tier", "title", "change", "evidence", "confidence"],
                },
            },
            "other_changes": {"type": "array", "items": {"type": "string"},
                              "description": "서버 내부 변경·리팩터링 한 줄 요약들"},
        },
        "required": ["summary", "items", "other_changes"],
    },
}


# ---------- 유틸 ----------
def sh(*args: str) -> str:
    # git 명령은 항상 레포 루트에서 실행한다. (하위 디렉터리에서 실행해도 pathspec이 깨지지 않도록)
    if args and args[0] == "git":
        args = ("git", "-C", _git_root()) + args[1:]
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


_ROOT_CACHE: list[str] = []


def _git_root() -> str:
    if not _ROOT_CACHE:
        root = subprocess.run(["git", "rev-parse", "--show-toplevel"],
                              check=True, capture_output=True, text=True).stdout.strip()
        _ROOT_CACHE.append(root)
    return _ROOT_CACHE[0]


def detect_repo() -> str:
    url = sh("git", "remote", "get-url", "origin").strip()
    m = re.search(r"github\.com[:/](.+?)(?:\.git)?$", url)
    return m.group(1) if m else ""


def github_token(cli_value: str | None) -> str | None:
    if cli_value:
        return cli_value
    if os.environ.get("GITHUB_TOKEN"):
        return os.environ["GITHUB_TOKEN"]
    if shutil.which("gh"):
        try:
            return sh("gh", "auth", "token").strip() or None
        except subprocess.CalledProcessError:
            return None
    return None


def gh_get(url: str, token: str | None) -> dict | None:
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "peakpic-release-notes",
        **({"Authorization": f"Bearer {token}"} if token else {}),
    })
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        if e.code in (403, 404):
            return None
        raise


# ---------- 수집 ----------
def collect_commits(base: str, head: str) -> list[str]:
    out = sh("git", "log", f"{base}..{head}", "--format=%h %s")
    return [l for l in out.splitlines() if l.strip()]


def collect_pr_numbers(base: str, head: str) -> list[int]:
    out = sh("git", "log", f"{base}..{head}", "--format=%s%n%b")
    nums = {int(n) for n in re.findall(r"#(\d+)", out)}
    return sorted(nums)


def fetch_prs(repo: str, numbers: list[int], token: str | None) -> list[dict]:
    prs = []
    for n in numbers:
        data = gh_get(f"https://api.github.com/repos/{repo}/pulls/{n}", token) if repo else None
        if data is None and repo:
            data = gh_get(f"https://api.github.com/repos/{repo}/issues/{n}", token)  # 이슈 번호일 수도
        if data:
            prs.append({
                "number": n,
                "title": data.get("title", ""),
                "body": (data.get("body") or "").strip(),
                "labels": [l["name"] for l in data.get("labels", [])],
                "url": data.get("html_url", ""),
                "kind": "pr" if "merged_at" in data else "issue",
            })
        else:
            prs.append({"number": n, "title": "", "body": "", "labels": [], "url": "", "kind": "unknown"})
    return prs


def priority_of(path: str) -> int:
    for p, rx in PRIORITY_RULES:
        if rx.search(path):
            return p
    return 4


def collect_diff(base: str, head: str, max_bytes: int) -> tuple[str, str, list[str], list[str]]:
    stat = sh("git", "diff", "--stat=120", base, head)
    files = [f for f in sh("git", "diff", "--name-only", base, head).splitlines() if f]
    files = [f for f in files if not any(rx.search(f) for rx in EXCLUDE_RULES)]
    files.sort(key=lambda f: (priority_of(f), f))

    chunks, included, skipped, size = [], [], [], 0
    for f in files:
        d = sh("git", "diff", base, head, "--", f)
        if size + len(d.encode()) > max_bytes:
            skipped.append(f)
            continue
        if not d.strip():
            continue  # 실제 내용 없는 파일(모드 변경 등)은 포함 수에서 제외 → 빈 diff 조기 감지
        chunks.append(d)
        included.append(f)
        size += len(d.encode())
    return stat, "".join(chunks), included, skipped


# ---------- 프롬프트 ----------
def build_user_prompt(repo: str, base: str, head: str, prs: list[dict], commits: list[str],
                      stat: str, diff: str, skipped: list[str]) -> str:
    pr_lines = []
    for p in prs:
        body = p["body"] if p["body"] else "(본문 없음)"
        pr_lines.append(f"### #{p['number']} {p['title']}\n{body}\n")
    skipped_note = ""
    if skipped:
        skipped_note = ("\n\n## diff 상한 초과로 제외된 파일(파일명만 참고)\n"
                        + "\n".join(f"- {s}" for s in skipped))
    return (
        f"# 릴리스 범위\n- 레포: {repo}\n- 구간: {base}..{head}\n\n"
        f"## 포함 PR/이슈\n" + ("\n".join(pr_lines) if pr_lines else "(없음)") +
        f"\n\n## 커밋 제목\n" + "\n".join(f"- {c}" for c in commits) +
        f"\n\n## 변경 파일 통계\n```\n{stat}\n```" + skipped_note +
        f"\n\n## 코드 diff\n```diff\n{diff}\n```\n"
    )


# ---------- LLM ----------
def call_anthropic(model: str, system: str, user: str) -> tuple[dict, dict]:
    try:
        import anthropic  # noqa: WPS433 (지연 import: dry-run은 SDK 없이도 동작)
    except ImportError:
        sys.exit("anthropic SDK가 없습니다: pip install -r requirements.txt")
    client = anthropic.Anthropic()
    resp = client.messages.create(
        model=model,
        max_tokens=4000,
        system=system,
        tools=[TOOL],
        tool_choice={"type": "tool", "name": "release_notes"},
        messages=[{"role": "user", "content": user}],
    )
    data = next((b.input for b in resp.content if b.type == "tool_use"), None)
    if data is None:
        sys.exit("모델이 구조화 출력을 반환하지 않았습니다.")
    usage = {"input_tokens": resp.usage.input_tokens, "output_tokens": resp.usage.output_tokens}
    return data, usage


def estimate_cost(model: str, usage: dict) -> str:
    if model not in PRICES:
        return "(단가 미등록 — PRICES에 추가하면 추정치를 표시합니다)"
    pin, pout = PRICES[model]
    usd = usage["input_tokens"] / 1e6 * pin + usage["output_tokens"] / 1e6 * pout
    return f"≈ ${usd:.4f} (≈ {usd * 1400:.0f}원, 단가 추정치 기준)"


# ---------- 규칙 검증: LLM이 인용한 식별자가 실제 입력에 있는지 ----------
IDENT_PATTERNS = [
    re.compile(r"\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b"),          # UPPER_SNAKE: 메시지 타입·에러코드
    re.compile(r"\b[a-z][a-z0-9]*(?:[-.][a-z0-9]+){2,}\b"),     # kebab/dotted 키: signaling.heartbeat.max-idle-ms
    re.compile(r"\b[A-Z][a-zA-Z0-9]+(?:Request|Response|Api|Handler|Service|Reaper|Manager)\b"),  # 클래스명
]
IDENT_ALLOWLIST = {"HTTP", "JSON", "JWT", "TTL", "ALB", "API", "AWS", "S3", "TURN", "STUN", "ICE", "SDP",
                   "PING", "PONG", "QR", "OS", "ID", "URL", "UTC", "KST", "DB", "SQL", "REST", "WS", "OK"}


def extract_idents(text: str) -> set[str]:
    found = set()
    for rx in IDENT_PATTERNS:
        found.update(m.group(0) for m in rx.finditer(text))
    return {t for t in found if t not in IDENT_ALLOWLIST and len(t) >= 4}


NEW_WORDS = ("신규", "추가", "새 ", "새로", "도입")


def ident_in_corpus(tok: str, corpus: str) -> bool:
    if tok in corpus:
        return True
    if "." in tok:  # yml 중첩 키(a.b.c)는 통째로 안 나오므로 잎 키가 'leaf:' 형태로 있는지 본다
        leaf = tok.rsplit(".", 1)[-1]
        return f"{leaf}:" in corpus or f"{leaf}=" in corpus
    return False


def verify_items(data: dict, corpus: str, diff: str) -> list[tuple[int, list[str]]]:
    """
    규칙 검증 두 가지:
      1) 항목이 인용한 식별자가 입력(corpus)에 실제로 있는가 → 없으면 '날조 의심'
      2) '신규/추가'라고 쓴 UPPER_SNAKE 이름이 diff의 + 줄에 있는가 → 문맥/‑ 줄에만 있으면 '이미 존재'
    위반 항목은 confidence=low + unverified_identifiers 에 사유 기록. 반환: (항목 index, 사유들)
    """
    added_lines = "\n".join(l for l in diff.splitlines() if l.startswith("+") and not l.startswith("+++"))
    problems = []
    for i, it in enumerate(data.get("items", [])):
        blob = " ".join(str(it.get(k, "")) for k in
                        ("title", "change", "required_action", "recommended_action", "deploy_order_note"))
        reasons = []
        idents = extract_idents(blob)
        for t in sorted(idents):
            if not ident_in_corpus(t, corpus):
                reasons.append(f"{t} (입력에 없음)")
            elif any(w in blob for w in NEW_WORDS) and t.isupper() and t not in added_lines:
                reasons.append(f"{t} (신규라고 했지만 diff의 + 줄에 없음 — 이미 존재하던 이름)")
        if reasons:
            problems.append((i, reasons))
            it["confidence"] = "low"
            it["unverified_identifiers"] = reasons
    return problems


# ---------- 렌더 ----------
AUD_LABEL = {"mobile": "📱 모바일", "ai": "🤖 AI 팀", "all": "👥 전원"}
CONF_LABEL = {"high": "", "medium": " (확인 권장)", "low": " (확인 필요 · 확신 낮음)"}


def render_md(data: dict, prs: list[dict], repo: str, base: str, head: str) -> str:
    items = data.get("items", [])
    req = [i for i in items if i["tier"] == "required"]
    fyi = [i for i in items if i["tier"] == "fyi"]

    def section(title: str, rows: list[dict]) -> str:
        if not rows:
            return ""
        out = [f"### {title}"]
        for aud in ("mobile", "ai", "all"):
            sub = [r for r in rows if r["audience"] == aud]
            if not sub:
                continue
            out.append(f"#### {AUD_LABEL[aud]}")
            for r in sub:
                out.append(f"- **{r['title']}**{CONF_LABEL.get(r['confidence'], '')}")
                out.append(f"  - 변경: {r['change']}")
                if r.get("required_action"):
                    out.append(f"  - 해야 할 일: {r['required_action']}")
                if r.get("recommended_action"):
                    out.append(f"  - 권장 조치: {r['recommended_action']}")
                if r.get("unverified_identifiers"):
                    out.append("  - ⚠️ 규칙 검증 실패 → 사람이 확인: " + "; ".join(r["unverified_identifiers"]))
                if r.get("deploy_order_note"):
                    out.append(f"  - 배포 순서: {r['deploy_order_note']}")
                if r.get("evidence"):
                    out.append(f"  - 근거: {', '.join(r['evidence'])}")
        return "\n".join(out) + "\n"

    pr_list = "\n".join(
        f"- #{p['number']} {p['title']}" + (f" ({p['url']})" if p["url"] else "") for p in prs
    ) or "- (없음)"
    other = "\n".join(f"- {o}" for o in data.get("other_changes", [])) or "- (없음)"

    md = [
        "## 릴리스 노트 (자동 초안 — 검토 후 publish)",
        f"_범위: `{base}..{head}` · 생성: LLM 초안, 사람이 확인_",
        "",
        "### 요약",
        data.get("summary", ""),
        "",
        section("🚨 선행조건 — 조치 필요 (required)", req),
        section("ℹ️ 참고 (fyi)", fyi),
        "### 기타 변경(서버 내부)",
        other,
        "",
        "### 포함 PR",
        pr_list,
        "",
        f"<!-- release-notes-json {json.dumps(data, ensure_ascii=False)} -->",
    ]
    return "\n".join(md)


# ---------- main ----------
def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", required=True, help="이전 릴리스 커밋/브랜치 (예: origin/main)")
    ap.add_argument("--head", required=True, help="이번 릴리스 커밋/브랜치 (예: develop)")
    ap.add_argument("--repo", default=None, help="owner/repo (기본: origin 원격에서 추론)")
    ap.add_argument("--model", default=DEFAULT_MODEL)
    ap.add_argument("--max-diff-bytes", type=int, default=60_000)
    ap.add_argument("--github-token", default=None)
    ap.add_argument("--out-dir", default=str(HERE / "out"))
    ap.add_argument("--md-out", default=None, help="마크다운 초안을 이 경로에도 저장(CI에서 Release 본문으로 사용)")
    ap.add_argument("--empty-ok", action="store_true",
                    help="다른 팀 공지 항목이 없으면 md에 그 사실만 남긴다(CI에서 draft 생성 여부 판단용)")
    ap.add_argument("--dry-run", action="store_true", help="API 호출 없이 프롬프트만 저장")
    args = ap.parse_args()

    repo = args.repo or detect_repo()
    token = github_token(args.github_token)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    tag = f"{args.base.replace('/', '_')}..{args.head.replace('/', '_')}"

    commits = collect_commits(args.base, args.head)
    numbers = collect_pr_numbers(args.base, args.head)
    prs = fetch_prs(repo, numbers, token)
    stat, diff, included, skipped = collect_diff(args.base, args.head, args.max_diff_bytes)

    system = (HERE / "prompt.md").read_text(encoding="utf-8")
    user = build_user_prompt(repo, args.base, args.head, prs, commits, stat, diff, skipped)
    (out_dir / f"prompt-{tag}.txt").write_text(system + "\n\n=====USER=====\n\n" + user, encoding="utf-8")

    print(f"[수집] 커밋 {len(commits)}개, PR/이슈 {len(numbers)}개 {numbers}, "
          f"diff 파일 {len(included)}개 포함 / {len(skipped)}개 제외, GitHub 토큰 {'있음' if token else '없음'}")
    if args.dry_run:
        print(f"[dry-run] 프롬프트 저장: {out_dir / f'prompt-{tag}.txt'}")
        return

    data, usage = call_anthropic(args.model, system, user)

    # 규칙 검증: LLM이 쓴 식별자가 실제 입력에 없으면 표시 + confidence 강등
    corpus = user  # PR 본문·커밋 제목·stat·diff 전부 포함
    problems = verify_items(data, corpus, diff)
    if problems:
        print(f"[검증] 입력에 없는 이름을 쓴 항목 {len(problems)}개 → ⚠️ 표시·confidence low")
        for i, missing in problems:
            print(f"   - 항목 {i + 1} '{data['items'][i]['title']}': {missing}")
    else:
        print("[검증] 모든 항목의 식별자가 입력에서 확인됨")

    md = render_md(data, prs, repo, args.base, args.head)
    (out_dir / f"notes-{tag}.md").write_text(md, encoding="utf-8")
    (out_dir / f"notes-{tag}.json").write_text(json.dumps(
        {"model": args.model, "usage": usage, "data": data}, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.md_out:
        Path(args.md_out).write_text(md, encoding="utf-8")

    has_items = bool(data.get("items"))
    print(f"[LLM] model={args.model} tokens in/out={usage['input_tokens']}/{usage['output_tokens']} "
          f"비용 {estimate_cost(args.model, usage)}")
    print(f"[출력] {out_dir / f'notes-{tag}.md'}")
    # CI가 파싱할 수 있게 GITHUB_OUTPUT에 요약을 남긴다(로컬에선 무시됨).
    gh_out = os.environ.get("GITHUB_OUTPUT")
    if gh_out:
        required = sum(1 for it in data.get("items", []) if it.get("tier") == "required")
        low = sum(1 for it in data.get("items", []) if it.get("confidence") == "low")
        with open(gh_out, "a", encoding="utf-8") as fh:
            fh.write(f"has_items={'true' if has_items else 'false'}\n")
            fh.write(f"required_count={required}\n")
            fh.write(f"low_conf_count={low}\n")
    print("\n" + md)


if __name__ == "__main__":
    main()
