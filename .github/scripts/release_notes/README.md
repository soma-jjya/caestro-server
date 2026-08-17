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

## CI에서의 사용
`release-notes.yml`이 main push 시 `--base <이전 릴리스 SHA> --head <현재 SHA>` 로 같은 스크립트를 실행하고
결과 md로 Release **draft**를 만든다. publish는 사람이 한다.
