# B2B 부하 검증 (#219)

축제 생성은 DB 데이터 생성이다. `/go/{slug}`와 `/go/{slug}/{code}`는 공통 FE 경로이며 축제마다 DNS·서버를 만들지 않는다. B2B와 B2C는 앱·DB·Redis를 공유한다. 이 문서는 공유 자원의 처리량과 공용 Wi-Fi 입장 제한을 구분해서 측정하는 절차다.

## 검증 범위

- `k6/b2b-session.js`: 공개 프로젝트 조회 → 신규 게스트 인증 → 프레임 id/version으로 네컷 생성 → 대기 중 반복 조회 → 원본 PNG 4장 업로드 → 완료 상태 조회.
- `k6/b2b-shared-ip.js`: 같은 출발 IP의 서로 다른 기기들이 같은 게스트 발급 한도를 사용하는지, 초과 시 429인지, 창 만료 뒤 다시 허용되는지 확인.
- `infra/monitoring/dashboards/peakpic-b2b.json`: 요청 처리·프로젝트 잠금 조회·기존 인증 제한·DB 풀·JVM 관측.
- `node --test load-test/k6/b2b/workflow.test.mjs`: 실제 k6가 호출하는 공통 흐름을 가짜 HTTP/시간으로 검증한다. 실제 서버 처리량을 측정하는 테스트는 아니다.

이번 구현은 운영 인증 한도, API 계약, 잠금 방식, 배포 인프라를 변경하지 않는다. 운영 부하 테스트를 자동 실행하지 않는다. DB 마이그레이션이나 FE 수정도 필요 없다.

PR CI의 `B2B load script contracts`는 Node 계약 테스트와 k6 1.0.0 초기화 검사 후 일회용 Compose에서 한 흐름의 smoke 및 공용 IP 제한/회복을 실행한다. 운영 트래픽이나 수용량 벤치마크가 아니다. Java 지표·MVC·실제 Redis TTL 테스트는 기존 전체 Java CI에서 실행한다.

## 먼저 격리된 환경 준비

운영 DB·Redis·버킷·AWS 자격 증명을 테스트 환경에 연결하지 않는다. 운영 호스트는 스크립트가 차단한다. 다른 원격 호스트는 `TEST_ENV=isolated`, `CONFIRM_TEST_HOST=<정확한 호스트>`를 지정해야 한다. 호스트 검사만으로 연결된 DB/버킷의 격리까지 보장할 수 없으므로 환경 구성을 확인한다.

로컬은 Docker와 k6, Python 3.9 이상이 필요하다. 저장소 루트에서 실행한다.

```powershell
python load-test/k6/b2b/generate-photo.py
docker compose -f load-test/docker-compose.b2b.yml up -d --build
curl.exe --fail http://localhost:8081/actuator/health/readiness
$env:BASE_URL = 'http://localhost:8081'
$env:STUDIO_KEY = 'local-b2b-studio-only'
$env:PROFILE = 'smoke'
k6 run load-test/k6/b2b-session.js
```

readiness가 200이 될 때까지 기다린 뒤 k6를 실행한다. Compose는 기존 개발 환경과 별도 프로젝트·DB·Redis를 사용하며 앱 포트만 localhost에 공개한다. 8081을 쓰는 다른 환경과 동시에 실행하지 않는다. 런타임 네트워크는 외부 통신을 차단하며 이미지·사진은 전용 MySQL에 저장한다. 앱 재시작 시 `create-drop`, MySQL 종료 시 tmpfs로 테스트 데이터가 사라진다. 이 설정은 운영에 적용하지 않는다.

**로컬 RDS 이미지 저장과 2 CPU/1 GiB 제한은 반복 가능한 기능·상대 성능 검증용이다. 운영 S3/CloudFront·DB 사양·CPU 크레딧·네트워크를 재현하지 않으므로 운영 수용 인원으로 환산하지 않는다.** 운영 규모 판단에는 같은 저장소 방식과 사양의 별도 스테이징이 필요하다.

## 1. 공용 IP 입장 제한 검증

```powershell
# 기본 30회/60초 설정인 로컬 환경에서, 다른 부하 테스트를 멈춘 뒤 실행
k6 run -e GUEST_LIMIT=30 -e GUEST_WINDOW_SECONDS=60 -e EXTRA_GUESTS=5 load-test/k6/b2b-shared-ip.js
```

한 IP에서 실행한다. 먼저 한 창을 기다린 뒤 35개 기기로 인증하여 30개 허용·5개 429를 기대한다. 다시 한 창을 기다려 회복을 확인한다. 이 통과는 **5명이 정상적으로 입장했다는 뜻이 아니라 정책에 따라 차단됐다는 뜻**이다. 제한값은 실제 대상 환경 설정과 맞춰야 한다. 테스트 중 동일 IP의 다른 요청이 있거나 첫 요청 묶음이 창 길이보다 오래 걸리면 결과를 유효한 측정으로 보지 않는다.

X-Forwarded-For를 임의로 넣어 IP를 분산하지 않는다. 로컬 직접 접속 검증과 별도로, 스테이징의 실제 프록시/ALB 경로에서 다시 검증해야 한다. 현재 서버는 X-Forwarded-For의 첫 IP를 사용하므로 신뢰할 수 있는 프록시의 헤더 처리 정책도 운영자가 확인한다.

## 2. 처리량 실험

`ARRIVALS_PER_MINUTE`는 HTTP RPS가 아니라 **새 네컷 참여 흐름 시작 수/분**이다. 매 흐름은 새 게스트를 발급받는다. 한 발생기 IP에서 분당 30회보다 많이 입장하면 기본 정책상 429가 먼저 발생한다. 이는 CPU/DB 처리량 한계와 구분해서 기록한다.

정책을 유지한 결과를 먼저 남긴 뒤, 서버 자체 처리량 실험이 필요할 때만 **위의 일회용 로컬 환경**에서 한도를 명시적으로 높일 수 있다.

```powershell
$env:B2B_LOAD_GUEST_LIMIT = '10000'
docker compose -f load-test/docker-compose.b2b.yml up -d app
# 앱 재시작/DDL 초기화가 있으므로 readiness 확인 후 매 실행마다 새 프로젝트를 만든다.
$env:PROFILE = 'steady'
New-Item -ItemType Directory -Force load-test/results/local-b2b | Out-Null
k6 run -e PROJECTS=1 -e ARRIVALS_PER_MINUTE=60 -e DURATION_SECONDS=300 -e HOLD_SECONDS=60 -e PREALLOCATED_VUS=100 -e MAX_VUS=200 --summary-export=load-test/results/local-b2b/steady.json load-test/k6/b2b-session.js
# 동일한 총 시작률로 축제 수만 3으로 바꾸어 프로젝트 행 경합을 비교
k6 run -e PROJECTS=3 -e ARRIVALS_PER_MINUTE=60 -e DURATION_SECONDS=300 -e HOLD_SECONDS=60 -e PREALLOCATED_VUS=100 -e MAX_VUS=200 load-test/k6/b2b-session.js
```

숫자는 실행 예시이며 수용량 보증이 아니다. 한도를 높인 결과로 행사장 공용 Wi-Fi 입장 가능 여부를 판단하지 않는다. 부하 발생기는 가능하면 앱과 다른 장비에서 실행하고 발생기의 CPU·메모리·네트워크도 기록한다.

| 설정 | 기본값 / 의미 |
| --- | --- |
| `PROFILE` | `smoke`: 프로젝트당 한 흐름. `steady`: 일정 시작률. `ramp`: 한 구간 상승 후 한 구간 유지. `spike`: 낮은 시작률 → 1초 상승 → 목표 유지 → 1초 하강 |
| `PROJECTS` | 1, 최대 20. 이번 실행이 새로 만든 프로젝트 사이에 균등 배분 |
| `ARRIVALS_PER_MINUTE` | 6, 전체 축제를 합친 목표 시작 수/분 |
| `DURATION_SECONDS` | 60. steady의 전체 시간 / ramp·spike의 각 주요 구간 시간 |
| `HOLD_SECONDS` | 20. 사진 4장을 올리기까지의 총 사용자 대기 시간. 요청 처리 시간은 별도 |
| `POLL_SECONDS` | 5. 대기 중 조회 간격. 각 업로드 전 대기 구간 끝에서도 조회 |
| `PREALLOCATED_VUS`, `MAX_VUS` | 10, 100. 발생기 동시 실행 공간. 목표율 미달은 `dropped_iterations` 실패 |
| `PHOTO_FILE` | `k6/b2b/generated/photo.png`. 생성 PNG는 600×800, 약 1.44 MB. 본인 준비 파일도 PNG, 1 KiB~3 MiB |
| `API_P95_MS`, `UPLOAD_P95_MS` | 1000, 3000. 초기 실험 기준으로 팀이 합의 후 조정 |

장시간 검증은 `PROFILE=steady`, `DURATION_SECONDS=1800` 등으로 설정한다. JWT 만료 30초 전 refresh하며 실패하면 해당 흐름을 종료한다. 재시도 루프로 장애 요청을 증폭하지 않는다. 기본 임계치는 참여 완료율 ≥99%, 전체 요청 실패율 <1%, 정리 실패 0, 목표 시작 누락 0이다. p95는 entry/create/read/upload 각각 확인한다. 초기 임계치는 합의된 서비스 SLO를 대신하지 않는다.

단일 k6 프로세스 실행을 기준으로 한다. drain 시간 초과 등으로 중단된 흐름은 `b2b_interrupted_iterations`가 1 이상이면 실패한다. Cloud/분산 발생기에서는 teardown의 인스턴스별 집계와 fixture 소유권을 별도로 설계해야 하므로 현재 스크립트를 그대로 사용하지 않는다.

참여 중 작성한 네컷은 측정 종료까지 남겨 DB 데이터 증가를 포함한다. 테스트 종료 후 프로젝트를 삭제하므로 완료 후 장기 보관/재조회 부하는 별도 시나리오가 필요하다. 현재 한 게스트가 한 네컷의 4장을 올리며, 여러 사용자가 같은 칸을 동시에 수정하는 경합·B2C 동시 부하·브라우저 합성/다운로드·CDN 전송량은 이 스크립트의 범위가 아니다. 업로드는 실제 API에 raw PNG로 전송한다. 응답의 외부 이미지 URL은 따라가지 않는다.

## 예상 인원을 부하 조건으로 바꾸기

- 피크 구간의 참여 시작 수를 구간 길이(초)로 나눈 값을 λ로 둔다. 하루 총 방문객만으로 동시 접속을 정하지 않는다.
- 평균 참여 시간이 T초이면 평균 진행 중 흐름은 대략 λ×T. 사진 업로드/네트워크 시간까지 포함한 실제 관측값으로 보정한다.
- 조회만의 대략적인 부하는 진행 중 흐름 수÷조회 간격. API 전체 요청 수에는 인증·입장·생성·사진 업로드도 더해야 한다.
- 네컷당 신규 사진 4장, 사진당 평균 B MB면 업로드 유입량은 대략 λ×4×B MB/초.
- 예: 10분에 1,000개 네컷 시작 → 약 100회/분. 평균 60초 진행이면 약 100개 진행 중 흐름. 5초 조회라면 조회만 약 20회/초. 실측 수용량이 아닌 시험 입력 계산이다.

축제마다 예상 참여율·집중 시간·사진 크기를 수집하고 겹치는 시간대의 시작률을 합산한다. 측정 없이 프로젝트 생성마다 서버를 할당하거나 Terraform을 실행하지 않는다. 기준 환경에서 여유가 없으면 병목에 맞춰 앱 증설, DB 풀/쿼리 검토, B2B 배포 분리 등을 후속 이슈로 결정한다.

## 지표 해석

새 `b2b.operation` 타이머는 `operation=entry|create|read|project_lock_lookup`, `outcome=success|client_error|server_error`만 사용한다. 총 12개 타이머에 고정 histogram bucket을 등록한다. Prometheus 이름은 `b2b_operation_seconds_count`, `_sum`, `_bucket`, `_max`다. 사용자·프로젝트·코드별 태그는 없다.

entry/create/read는 컨트롤러에서 서비스 호출과 트랜잭션 종료를 포함한다. 인증 필터, 요청 검증, JSON 직렬화, 네트워크 전송은 제외되므로 최종 HTTP 상태는 기존 `http_server_requests_seconds_count`와 k6 결과도 확인한다. create에는 FillWriteGuard 거절을 포함한다.

`project_lock_lookup`은 `findLockedBySlug` 조회 시간이다. 커넥션 획득·SQL·행 잠금 대기가 섞여 있으며, 조회 결과가 없는 것도 쿼리 실행 자체는 success다. 이를 순수 잠금 대기 시간으로 부르지 않는다. MySQL Performance Schema/Performance Insights의 행 잠금과 실행 SQL, DB 풀 대기 지표를 함께 본다. DB 설정이나 권한은 이 작업에서 변경하지 않는다.

기존 모니터링의 Prometheus 데이터소스에 `infra/monitoring/dashboards/peakpic-b2b.json`을 추가한다. 기존 수집/인증 경로를 그대로 사용하며 새 공개 지표 엔드포인트를 만들지 않는다. 로컬 metrics 토큰은 `local-b2b-metrics-only`이고 대상은 `http://localhost:8081/actuator/prometheus`다. 컨테이너 Prometheus에서 호스트에 접근할 때는 해당 환경의 호스트 게이트웨이 주소를 사용한다.

핵심 PromQL:

```promql
# 작업별 처리율. lock lookup은 create 내부 측정이므로 둘을 합산하지 않는다.
sum by (operation, outcome) (rate(b2b_operation_seconds_count[5m]))
# 인스턴스를 합산한 작업별 p95 (초)
histogram_quantile(0.95, sum by (le, operation) (rate(b2b_operation_seconds_bucket[5m])))
# 실제 HTTP 거절. uri는 Spring 경로 템플릿이며 raw slug/code가 아니다.
sum by (uri, status) (rate(http_server_requests_seconds_count{uri=~"/b2b/.*|/auth/guest|/fills/.*",status=~"4..|5.."}[5m]))
# 아래 두 지표는 B2C도 포함하는 전체 인증/쓰기 제한이다. 축제별 거절로 해석하지 않는다.
sum(rate(auth_guest_rate_limited_total[5m]))
sum by (kind) (rate(fill_rate_limited_total[5m]))
# DB 풀 대기, 인스턴스 CPU, JVM heap
hikaricp_connections_pending
process_cpu_usage
sum by (instance) (jvm_memory_used_bytes{area="heap"})
```

기존 지표가 아직 생성되지 않은 경우 `No data`를 성공/0으로 단정하지 않는다. 짧은 smoke 결과에는 5분 rate/p95가 안정적이지 않다. 개별 인스턴스 과부하, 메모리 증가, DB/Redis 지연, 스토리지 오류, 발생기 누락을 함께 기록한다. 장애가 연속되거나 팀의 비용/자원 중단 기준을 넘으면 새 참여 유입을 멈추고 drain과 정리를 확인한다.

## 정리와 실패 대응

setup에서 생성한 `lt-b2b-<run>-<index>` 프로젝트 ID/slug를 기록한다. teardown은 **해당 ID 단건 조회 → slug와 description 표식 일치 확인 → LIVE이면 pause → delete**만 수행한다. 전체 목록 검색으로 일괄 삭제하지 않으며 운영 축제 ID를 입력받지 않는다. setup 중 실패해도 이미 생성된 프로젝트는 같은 방식으로 정리한다.

강제 종료, 타임아웃 또는 응답 유실로 teardown/생성 ID 기록이 끝나지 않을 수 있다. 출력된 테스트 ID와 실행 표식을 확인해 격리 환경에서 정리한다. 삭제 204는 DB 삭제 완료이며 스토리지 삭제 outbox 재시도는 별도 확인해야 한다. 게스트 사용자·인증 Redis 키와 카탈로그 원본 이미지에는 개별 정리 API가 없으므로 일회용 DB/Redis를 사용한다. 이 때문에 공유 스테이징에 반복 실행해도 모든 데이터가 자동 소거된다고 보장하지 않는다.

로컬 작업 종료 시 위 Compose만 종료한다. 다른 프로젝트의 컨테이너나 볼륨을 제거하지 않는다.

```powershell
docker compose -f load-test/docker-compose.b2b.yml down
Remove-Item Env:B2B_LOAD_GUEST_LIMIT -ErrorAction SilentlyContinue
Remove-Item Env:STUDIO_KEY -ErrorAction SilentlyContinue
```

## 결과 기록 양식

`results/local-b2b/`는 git 제외다. 리뷰에 공유할 결과는 비밀값/개인정보를 제거한 뒤 별도 날짜 폴더에 저장한다.

| 항목 | 기록 |
| --- | --- |
| 커밋 / 실행 시각 / 도구 버전 | |
| 앱 인스턴스 수·사양 / DB·Redis·스토리지 구성 | |
| 발생기 사양 / 대상 환경 / 프록시 경로 | |
| 인증 한도 / 정책 유지 시험인지 처리량 분리 시험인지 | |
| 프로젝트 수 / 시작률 / 대기·조회 시간 / 사진 bytes | |
| 목표·실제 시작 수 / dropped_iterations / 중단된 흐름 | |
| 완료율 / API별 p95·p99 / 429·5xx / 업로드 처리량 | |
| 프로젝트 조회 시간 / DB 풀 대기 / CPU·heap·Redis·스토리지 | |
| 정리 결과 / 잔여 사용자·이미지·outbox 처리 | |
| 한계 / 다음 단계 / 행사 운영 가능 여부 | 미측정이면 미판정 |

참고: [k6 실행 수명과 setup 실패 시 정리](https://grafana.com/docs/k6/latest/using-k6/test-lifecycle/), [arrival-rate 모델](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/), [Micrometer Timer](https://docs.micrometer.io/micrometer/reference/concepts/timers.html).
