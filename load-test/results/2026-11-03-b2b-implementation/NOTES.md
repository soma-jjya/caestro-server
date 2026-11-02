# #219 구현 검증 — 수용량 측정 결과 아님

- 리뷰 브랜치: `feat/#219-review` ([PR #221](https://github.com/soma-jjya/caestro-server/pull/221))
- 로컬 기준 커밋: `afb365e` (develop에 병합된 기존 작업)
- GitHub 조회 기준 develop: `f902ffa33fca57a7dcbff05482a0b5a4f29bd90f`
- 기준 이후 develop 변경 파일과 이번 변경 파일은 겹치지 않음. 최신 develop 통합 실행은 PR CI에서 확인 필요.

## 완료한 검증

- `bootJar` 및 선택한 Java 테스트: 104개, 실패 0, 오류 0, 건너뜀 0.
  - `*B2b*`, `*AuthControllerTest`, `*CatalogStudioSecurityWebTest`, `*CatalogImageRedirectWebTest`, `*StudioKeyFilterTest`
  - B2B 기존 기능·MVC 보안 회귀, 새 지표 반환값/예외 보존, Prometheus bucket 이름, 공용 IP별 인증 키/차단 카운터 포함.
  - Windows 샌드박스의 JDK JAR 경로 접근 제한 때문에 저장소 밖 임시 Gradle init script로 캐시 JAR을 TEMP로 복사하고 별도 build 디렉터리를 사용했다. 소스/의존성 버전은 그대로이며 보통의 전체 Gradle CI 실행과 구분한다.
- `node --test load-test/k6/b2b/workflow.test.mjs`: 12개 통과. HTTP/시간을 주입한 오프라인 계약 테스트.
- Compose 설정 파싱, 두 k6 진입 파일의 JavaScript 구문 검사, Grafana dashboard JSON 파싱 통과.
- 생성 PNG: 600×800, 1,441,303 bytes. PNG chunk CRC와 압축 해제 크기 확인.
- `git diff --check` 통과.

## 미실행 / 후속 확인

- 로컬 k6 미설치, Docker 데몬 연결 불가로 실제 API smoke와 공유 IP Redis 창 만료 시험 미실행.
- `RedisRateLimiterTest.expiredWindowAllowsNewGuestBudget`의 실제 Redis 검증은 기존 Java CI에 포함되며 로컬 실행 결과에는 포함하지 않았다.
- PR #221의 `c7c6a45`에서 [Java CI](https://github.com/soma-jjya/caestro-server/actions/runs/41508689755)와 [B2B contracts](https://github.com/soma-jjya/caestro-server/actions/runs/41508689799)는 통과했다.
- 같은 B2B 실행에서 smoke는 앱 기동 뒤 호스트 `localhost:8081` readiness 대기 시간 초과로 실패했다. 앱 로그에는 정상 시작이 기록됐고 참여 흐름/공용 IP 단계는 실행되지 않았다. `internal: true` 네트워크에 호스트 포트 전달을 기대한 구성을 수정했다. 앱 내부 healthcheck와 Compose `--wait`, 앱 네트워크를 공유하는 k6로 변경했으며, 실패 시 healthcheck 출력도 남긴다. 수정 후 실제 smoke/공용 IP 통과는 push 후 CI에서 확인해야 한다.
- 운영/스테이징 부하 테스트 및 CPU·DB·Redis·스토리지 병목 실측 미실행.
- **동시 접속 수용량, 축제별 서버 할당량, 운영 가능 인원은 아직 미판정.**

다음 측정은 [B2B 실행 안내](../../B2B.md)의 입력 조건과 결과 양식으로 남긴다.
