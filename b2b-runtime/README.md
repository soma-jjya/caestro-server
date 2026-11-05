# PeakPic B2B 독립 서버

현재 단계는 **독립 실행 가능한 참가 서버와 인프라/검증 도구**다. 기존 B2C 원본, 기존 DB/Redis/S3, 기존 배포 workflow는 변경하지 않았다. AWS 배포·실측 용량 검증·중앙 관리자 자동화까지 완료된 상태는 아니다.

## 저장소와 브랜치

- 작업 브랜치: `feat/b2b-isolated-runtime` (새 이슈 번호 발급 전 임시 이름).
- [이슈 등록 본문](ISSUE.md), [API/FE 계약](API.md), [운영 연결 순서](OPERATIONS.md).
- 이 디렉터리만 새 저장소 **`peakpic-b2b-server`**의 루트로 옮긴다. 기존 저장소 `main`은 모든 push에 B2C 배포가 실행되므로, 이 작업을 그 `main`으로 릴리스하지 않는다.
- 이 디렉터리의 `.github/workflows`는 독립 저장소의 루트에 있어야 실행된다. 현재 caestro-server 내부의 중첩 workflow는 GitHub가 자동 실행하지 않는다.
- 기준 엔진은 `c867e00` 사본이다. 최신 원격 develop은 작업 시작 시 `ec832ead`로 조회했지만 git fetch 연결 제한으로 가져오지 못했다. 최신 인증 변경을 B2C에서 제거하거나 되돌린 것은 아니다. 이관 전에 이후 B2B/Fill 수정 여부를 비교한다.

## 포함한 기능

| 구분 | 구현 |
|---|---|
| 실행 환경 | 독립 Gradle/Boot 앱, Docker 이미지, 명시적인 축제/런타임 DB 바인딩 |
| 게스트 | 자체 JWT, 일회용 refresh, Redis 공유 IP 한도 30/60초와 런타임 전체 한도, 위조 전달 IP 거부 |
| 운영자 | 서버 간 `X-B2B-Admin-Key`, 이 런타임의 한 프로젝트/프레임만 수정 |
| 참가 | 프레임 1~3개, 창 4개, 생성 시 프레임 스냅샷, 네컷 생성·조회·사진 업로드 |
| 보관 | 행사 종료부터 쓰기 금지, `downloadsUntil`까지 조회 허용, 생성 시 DB 만료 시각도 그 기한으로 저장 |
| 저장소 | B2B 공용 S3, 환경/프로젝트/DB 실행 세대별 prefix, B2B 전용 CDN의 항상 붙는 CORS |
| Terraform | 새 S3/CDN/ECR, 축제별 EC2 ASG/ALB/MySQL/Redis, t3.micro 2대 출발, 행사 시작·종료 예약 |
| 시험 | H2 통합 테스트, MySQL/Redis Compose smoke, k6 참여·공용 IP probe, 한 변수씩 비교하는 용량 실험 기록 |
| CI | Java/도구/인프라 검증, Compose/k6 smoke, 수동 실행하는 검증 후 B2B ECR 이미지 게시 |

원본 네컷 엔진은 `engine/`에 출처/해시와 함께 고정했다. 기존 User/Fill/Catalog 테이블 이름도 **새 B2B DB 안에서만** 재사용한다. 소셜 인증·결제·시그널링·B2C Controller·기존 SecurityConfig는 포함하지 않는다. 프레임 카탈로그의 일부 빈 엔티티는 기존 저장소 쿼리 의존성 때문에 포함한다. 앱에 노출하는 API는 없다.

`python tools/check_engine.py`가 복사본의 변경/파일 추가를 감지한다. 원본 엔진 변경을 자동으로 따라가지 않으며 후속 수정은 별도 검토한다.

## 로컬 확인

```powershell
cd b2b-runtime
.\gradlew.bat test bootJar --console=plain
python tools/check_engine.py
python -m unittest discover -s tools -p "test_*.py"
node --test load/config.test.mjs
```

Docker 엔진이 실행 중인 경우, 아래 Compose는 **폐기 가능한 테스트 전용 DB**를 만든다. 운영용 설정으로 재사용하지 않는다.

```powershell
docker compose -f compose.test.yml up -d --build --wait --wait-timeout 180
docker compose -f compose.test.yml --profile tools run --rm tester
python tools/make_photo.py
docker compose -f compose.test.yml --profile tools run --rm k6 run load/participants.js
docker compose -f compose.test.yml --profile tools run --rm k6 run load/shared-ip.js
docker compose -f compose.test.yml down --volumes --remove-orphans
```

smoke는 빈 프로젝트에서 시작한다. 다시 실행하려면 위 테스트 환경을 내린 뒤 새로 시작한다. 생성하는 사진은 합성 데이터다. MySQL/Redis 포트는 호스트에 노출하지 않고 앱만 `127.0.0.1:18090`에 바인딩한다. 테스트 도구는 앱의 네트워크 공간을 공유하므로 외부 네트워크에 의존하지 않는다.

## 아직 연결해야 하는 단계

1. 독립 GitHub 저장소와 B2B 전용 AWS 배포 역할/계정·Terraform state·인증서·DNS 준비.
2. CI에서 Terraform provider 검증과 실제 MySQL/Redis/k6 실행 확인.
3. B2B S3/CloudFront에서 실제 업로드·캔버스 합성·삭제 범위 검증 및 운영 사양으로 부하 실측.
4. 중앙 관리자에 예상 참여 인원/행사 기간/자원 상한 입력과 작업 상태 저장 API 연결.
5. 환경 준비→시험→공개 라우팅→행사 종료→보관 종료 정리를 수행하는 영속 작업 큐/재시도 조정기 구현.
6. 기존 B2B 프로젝트의 데이터 이관/전환 계획. 현재 운영 yuja와 기존 B2C에는 자동 전환하지 않는다.

현재 `capacity_plan.py`는 시험 입력을 계산하며 AWS를 생성하지 않는다. Terraform은 실행 가능한 구성 초안이지만 아직 AWS provider validate/plan/apply를 통과한 것은 아니다. CI의 smoke도 수용 인원 보장이 아니다.

운영 방식은 **작은 운영 사양 그대로 시험 → 실패 원인 하나씩 변경 → 검증된 설정을 B2B 운영에 반영 → 스테이징 정리**다. RDS/Redis의 정확한 현재 사양과 T3 credit mode는 필수 확인값으로 남겨 두었다. IP 한도에 막힌 정상 참가자는 증설로 해결되지 않으므로 용량과 별도로 판정한다. [운영 절차](OPERATIONS.md), [실험 결과 보관](load-test/results/README.md)을 따른다.

## 커밋 구성

리뷰를 위해 아래 네 단위로 커밋을 나눴다. 새 이슈 번호는 아직 발급되지 않아 기존 작업 브랜치 `feat/b2b-isolated-runtime`을 유지한다. 원격 게시 여부는 GitHub 브랜치에서 별도로 확인한다.

1. 재사용 엔진 출처 고정: `engine/`, wrapper, `tools/vendor_engine.py`, `tools/check_engine.py`.
2. 독립 런타임: `src/`, Gradle 설정, Dockerfile, `.dockerignore`, `.gitattributes`, `.gitignore`.
3. 검증/인프라: `infra/`, `load/`, `load-test/`, `compose.test.yml`, `.github/`, 나머지 `tools/`와 예시 입력.
4. 이슈·API·운영 문서.

로컬 검증 결과는 [VALIDATION.md](VALIDATION.md)에 기록한다.
