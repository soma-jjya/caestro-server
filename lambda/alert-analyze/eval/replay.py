"""골든 셋 재생: 과거 알람을 픽스처 도구로 재현해 실제 모델이 사람의 조사 결론과 같은 결론을 내는지 본다.

    export ANTHROPIC_API_KEY=...
    python3 eval/replay.py                 # 전체
    python3 eval/replay.py --case 2026-09-29   # 이름에 포함된 케이스만
    python3 eval/replay.py --model claude-opus-5-5 --runs 3

판정: category·user_impact 가 기대 목록 안에 있고, mentions_any 중 하나 이상이 보고서에 등장하면 통과.
"""

import argparse
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

from fixtures import FixtureTools  # noqa: E402
from investigate import DEFAULT_MODEL, investigate  # noqa: E402

CASE_DIR = pathlib.Path(__file__).parent / "cases"


def grade(case, report):
    expect = case["expect"]
    if report is None:
        return False, ["보고서 없음"]
    problems = []
    if report["category"] not in expect["category"]:
        problems.append(f"category {report['category']} ∉ {expect['category']}")
    if report["user_impact"] not in expect["user_impact"]:
        problems.append(f"user_impact {report['user_impact']} ∉ {expect['user_impact']}")
    blob = json.dumps(report, ensure_ascii=False)
    mentions = expect.get("mentions_any") or []
    if mentions and not any(m in blob for m in mentions):
        problems.append(f"언급 없음: {mentions}")
    if not report.get("evidence"):
        problems.append("근거 0건")
    return not problems, problems


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", default="")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--runs", type=int, default=1, help="케이스당 반복 횟수(일관성 확인)")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    import anthropic

    client = anthropic.Anthropic()
    paths = [p for p in sorted(CASE_DIR.glob("*.json")) if args.case in p.name]
    if not paths:
        sys.exit("해당 케이스 없음")

    total = passed = 0
    tokens_in = tokens_out = 0
    for path in paths:
        case = json.loads(path.read_text())
        for run in range(args.runs):
            tools = FixtureTools(case["fixtures"])
            report, trace, _ = investigate(case["alarm"], tools, client, model=args.model)
            ok, problems = grade(case, report)
            total += 1
            passed += ok
            tokens_in += trace["input_tokens"]
            tokens_out += trace["output_tokens"]
            mark = "✓" if ok else "✗"
            headline = (report or {}).get("headline", "-")
            print(f"{mark} {path.stem} #{run + 1}: {headline}")
            print(f"    도구 {trace['tool_calls']} · 왕복 {trace['steps']} · 토큰 {trace['input_tokens']}/{trace['output_tokens']} · {trace['elapsed_s']}s")
            for p in problems:
                print(f"    - {p}")
            if args.verbose and report:
                print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"\n{passed}/{total} 통과 · 총 토큰 {tokens_in}/{tokens_out}")
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
