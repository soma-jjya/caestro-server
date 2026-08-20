# 릴리스 노트 초안 생성기 (LLM)

develop → main 릴리스 구간의 PR·커밋·diff를 모아 **다른 팀(모바일/AI/전원)이 알아야 할 변경**을
required(선행조건)/fyi로 분류한 릴리스 노트 초안을 만든다. 사람이 검토·수정 후 publish하는 것이 전제.

- 규칙: diff·PR 본문에 근거 없는 내용은 쓰지 않는다(프롬프트 규칙 + `evidence` 필수 + `confidence`).
- 출력: `out/notes-<base>..<head>.md`(초안), `.json`(구조화 결과 + 토큰 사용량), `prompt-*.txt`(조립된 입력).
- 비용: 릴리스 1회당 입력 10~25K 토큰 수준 → 수십~백여 원(모델별 `PRICES` 참고, 추정치).

## 로컬 실행 (백필 검증)

```bash
cd .github/scripts/release_notes
python3 -m venv venv && source venv/bin/activate
pip install -r requirements.txt

export ANTHROPIC_API_KEY=sk-ant-...           # 콘솔에서 발급, 커밋 금지
gh auth login                                  # (권장) PR 제목/본문을 프롬프트에 포함시키기 위해
                                               #   없으면 GITHUB_TOKEN=ghp_... 로도 가능. 둘 다 없으면 커밋 제목만 사용

# 과거 릴리스 재생성 (origin/main 의 릴리스 병합 커밋 기준)
python3 generate.py --base 92052fc --head 1c4c9e5   # 릴리스 PR #57 — 하트비트(#54/#55)·6자 코드(#52) 포함
python3 generate.py --base 1c4c9e5 --head 4504bd8   # 릴리스 PR #65 — #59 탈퇴 API, #61/#62 배치, #63/#64 Lambda

# 기본 모델은 claude-haiku-4-5 (릴리스 1회 ≈ 25~35원). 품질 비교용으로 Sonnet 한 번:
python3 generate.py --base 92052fc --head 1c4c9e5 --model claude-sonnet-5

# API 호출 없이 입력만 확인
python3 generate.py --base origin/main --head develop --dry-run
```

## 백필 검증 기준 (이슈 #67 인수 조건)
- #57: `mobile / required` 로 **"클라이언트가 주기적으로 PING을 보내야 함(미전송 시 90초 후 세션 종료)"** 이 나와야 한다.
  6자 코드 변경도 mobile 항목으로 잡히면 좋다(confidence는 낮아도 됨).
- #65: 배치·ShedLock은 `other_changes`(서버 내부), 탈퇴 API는 `mobile / fyi`(새 엔드포인트),
  Lambda 썸네일은 `ai`(orig만 올리면 thumb/preview 자동 생성) 로 분류되면 합격.
- 사람이 직접 매긴 판정과 비교해 정확도를 기록한다(포폴 증거).

## 골든 셋 평가 (evals)

LLM이 "팀에 알렸어야 할 것"을 얼마나 정확히 골라내는지 과거 릴리스 8개로 자동 채점한다.
프롬프트/모델을 바꿀 때마다 재실행 → 품질 회귀 테스트.

```bash
cd .github/scripts/release_notes && source venv/bin/activate
python3 eval/run_eval.py                # 전체 8구간 (캐시 없는 구간은 LLM 호출, 총 ~300원)
python3 eval/run_eval.py --cached-only  # 이미 생성된 결과만 채점 (무료)
python3 eval/run_eval.py --model claude-sonnet-5   # 모델 비교
```

- 정답 라벨: `eval/golden.yaml` — ⚠️ 초안은 자동 생성됨. 각 릴리스의 expected를 검수 후 `verified: true`로.
- 지표: 필수 항목 재현율 / 등급(required·fyi) 정확도 / 대상 정확도 / 규칙 검증 검출(날조) / 골든 셋 밖 항목 수.
- 프롬프트 수정 전후로 돌려 결과를 아래 "평가 기록"에 남긴다.

### 평가 기록 (모델: haiku-4.5, 8개 릴리스 백필)
| 버전 | 재현율 | 등급 | 대상 | 날조 플래그 | 고친 것 |
|---|---|---|---|---|---|
| v1 | 56% | 57% | 100% | 6건 | (기준선) |
| v3 | 80% | 70% | 100% | 3건 | 이름은 diff 문자열 그대로 / "신규"는 +줄에 있어야 / **교차 오염 금지**(PR 본문에만 있는 과거 작업 항목화 금지 — R3에서 실검증) |
| v4 | 80% | **100%**¹ | 100% | 3건 | **리트머스 질문**: "안 바꾸면 기존이 깨지나?" 아니오면 무조건 fyi (takeover·유예·탈퇴 API가 fyi로 정착) |
| v4+입력수정 | **100%** | 92% | 100% | 4건 | **diff 예산: 같은 우선순위 내 변경량 큰 파일 먼저 + 상한 60→120KB** + max_tokens 8000·불완전 출력 재시도 |

¹ v4 시점 표본에는 R2(입력 잘림으로 items 0)가 미포함.

**핵심 교훈 2가지 (둘 다 "LLM이 이상하면 프롬프트 전에 입력을 의심하라")**
1. CWD 버그로 diff가 빈 채 전송 → LLM이 그럴듯한 키 이름을 날조 → 규칙 검증 층이 검출.
2. R2 items 0의 원인은 LLM이 아니라 **알파벳순 diff 예산이 핵심 파일(SignalingService)을 굶긴 것** — "diff에 없으면 항목화 금지" 가드레일은 정확히 동작했고, 입력을 고치자 재현율 100%.

**운영 판정**: 재현율 100%(누락 0) + 등급 오류는 required 과잉(안전한 방향) + 과잉 보고는 draft 검토에서 제거 → 투입 가능. 백필은 Team Notice 없던 옛 PR 기준이라 실운영 품질은 이보다 높을 것으로 예상. 릴리스마다 draft 수정 항목 수를 기록해 실측정 지속.

## CI에서의 사용
`release-notes.yml`이 main push 시 `--base <이전 릴리스 SHA> --head <현재 SHA>` 로 같은 스크립트를 실행하고
결과 md로 Release **draft**를 만든다. publish는 사람이 한다.
