#!/usr/bin/env python3
"""
릴리스 노트 LLM 골든 셋 평가 (evals).

golden.yaml의 각 릴리스 구간에 대해 generate.py 결과(out/notes-*.json)를 채점한다.
- 결과 파일이 없으면 generate.py를 호출해 생성한다 (ANTHROPIC_API_KEY 필요, 구간당 ~40원).
- --cached-only: 이미 생성된 결과만 채점 (API 호출 없음)

지표:
  재현율        = 매칭된 must 항목 / 전체 must 항목
  등급 정확도   = 매칭된 항목 중 tier(required/fyi) 일치 비율
  대상 정확도   = 매칭된 항목 중 audience 일치 비율
  날조(검출)    = unverified_identifiers가 붙은 LLM 항목 수 (규칙 검증이 잡은 것)
  extras        = 골든 셋에 없는 LLM 항목 수 (틀림이 아니라 검토 대상)

사용:
  python3 run_eval.py                 # 전체 (없는 구간은 LLM 호출)
  python3 run_eval.py --cached-only   # 캐시만
  python3 run_eval.py --model claude-sonnet-5   # 모델 비교
"""

import argparse
import json
import subprocess
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.exit("pip install -r ../requirements.txt (pyyaml 필요)")

HERE = Path(__file__).resolve().parent
NOTES_DIR = HERE.parent          # generate.py 위치
OUT_DIR = NOTES_DIR / "out"


def notes_path(base: str, head: str) -> Path:
    return OUT_DIR / f"notes-{base}..{head}.json"


def ensure_notes(base: str, head: str, model: str | None, cached_only: bool) -> dict | None:
    p = notes_path(base, head)
    if not p.exists():
        if cached_only:
            return None
        cmd = [sys.executable, str(NOTES_DIR / "generate.py"), "--base", base, "--head", head]
        if model:
            cmd += ["--model", model]
        print(f"  [생성] {base}..{head} → LLM 호출")
        subprocess.run(cmd, check=True, capture_output=True, text=True)
    return json.loads(p.read_text(encoding="utf-8"))


def item_text(it: dict) -> str:
    return " ".join(str(it.get(k, "")) for k in
                    ("title", "change", "required_action", "recommended_action")).lower()


def evaluate(release: dict, result: dict) -> dict:
    items = result["data"].get("items", [])
    matched_llm_idx: set[int] = set()
    rows = []
    for exp in release.get("expected", []):
        kws = [k.lower() for k in exp["keywords"]]
        hit = None
        for i, it in enumerate(items):
            if i in matched_llm_idx:
                continue
            if any(k in item_text(it) for k in kws):
                hit = (i, it)
                break
        if hit:
            i, it = hit
            matched_llm_idx.add(i)
            rows.append({
                "key": exp["key"], "found": True, "must": exp.get("must", True),
                "tier_ok": it.get("tier") == exp["tier"],
                "aud_ok": it.get("audience") == exp["audience"],
                "got": f"{it.get('audience')}/{it.get('tier')}",
                "want": f"{exp['audience']}/{exp['tier']}",
            })
        else:
            rows.append({"key": exp["key"], "found": False, "must": exp.get("must", True)})
    extras = [it for i, it in enumerate(items) if i not in matched_llm_idx]
    hallucinated = [it for it in items if it.get("unverified_identifiers")]
    return {"rows": rows, "extras": extras, "hallucinated": hallucinated, "n_items": len(items)}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cached-only", action="store_true")
    ap.add_argument("--model", default=None)
    args = ap.parse_args()

    golden = yaml.safe_load((HERE / "golden.yaml").read_text(encoding="utf-8"))
    releases = golden["releases"]

    tot_must = tot_must_found = tot_matched = tot_tier_ok = tot_aud_ok = 0
    tot_hall = tot_extras = 0
    skipped = []
    unverified_golden = [r["name"] for r in releases if not r.get("verified")]

    for rel in releases:
        print(f"\n== {rel['name']}  ({rel['base']}..{rel['head']})")
        try:
            result = ensure_notes(rel["base"], rel["head"], args.model, args.cached_only)
        except subprocess.CalledProcessError as e:
            print(f"  [오류] 생성 실패: {e.stderr[-300:] if e.stderr else e}")
            skipped.append(rel["name"])
            continue
        if result is None:
            print("  [건너뜀] 캐시 없음 (--cached-only)")
            skipped.append(rel["name"])
            continue

        ev = evaluate(rel, result)
        for r in ev["rows"]:
            if r["found"]:
                mark_t = "" if r["tier_ok"] else f"  [등급 다름: got {r['got']} / want {r['want']}]"
                mark_a = "" if r["aud_ok"] else "  [대상 다름]"
                print(f"  ✓ {r['key']}{mark_t}{mark_a}")
                tot_matched += 1
                tot_tier_ok += r["tier_ok"]
                tot_aud_ok += r["aud_ok"]
            else:
                flag = "✗ 누락(must)" if r["must"] else "– 미검출(optional)"
                print(f"  {flag} {r['key']}")
            if r["must"]:
                tot_must += 1
                tot_must_found += r["found"]
        if ev["hallucinated"]:
            print(f"  ⚠️ 규칙 검증 걸린 항목 {len(ev['hallucinated'])}건 (confidence 강등됨)")
        if ev["extras"]:
            titles = ", ".join(it.get("title", "?")[:30] for it in ev["extras"][:3])
            print(f"  + 골든 셋 밖 항목 {len(ev['extras'])}건: {titles}")
        tot_hall += len(ev["hallucinated"])
        tot_extras += len(ev["extras"])

    print("\n" + "=" * 60)
    print("종합")
    if tot_must:
        print(f"  필수 항목 재현율   : {tot_must_found}/{tot_must} ({100 * tot_must_found / tot_must:.0f}%)")
    if tot_matched:
        print(f"  등급 정확도        : {tot_tier_ok}/{tot_matched} ({100 * tot_tier_ok / tot_matched:.0f}%)")
        print(f"  대상 정확도        : {tot_aud_ok}/{tot_matched} ({100 * tot_aud_ok / tot_matched:.0f}%)")
    print(f"  규칙 검증 검출     : {tot_hall}건 (⚠️로 표시되어 사람 확인 유도)")
    print(f"  골든 셋 밖 항목    : {tot_extras}건 (검토 대상, 감점 아님)")
    if skipped:
        print(f"  건너뜀             : {len(skipped)}개 구간")
    if unverified_golden:
        print(f"\n  ⚠️ 검수 안 된 골든 셋 {len(unverified_golden)}개 — golden.yaml에서 정답 확인 후 verified: true로 변경하세요.")


if __name__ == "__main__":
    main()
