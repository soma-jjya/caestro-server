# 검증 기록 — 2026-11-04

## 로컬 완료

- Java 21 / Gradle 9.5.1 독립 `test bootJar`: 성공. XML 기준 **13개, 실패/오류 0**. H2 데이터베이스를 이용한 MVC 통합 테스트 포함.
- 게스트 발급 → 프레임 1장 공개 → 생성 → 4칸 업로드/이미지 읽기 → PAUSED 기존 조회.
- 다른 프로젝트/런타임/issuer JWT, 만료 JWT, 잘못된 운영자 키, 미인증 요청 거부.
- refresh 일회성/Redis 해시 키, S3 세션 삭제 prefix와 CDN 조회 키, 기존 DB 초기화 거부.
- 행사 종료 후 쓰기 차단과 보관 기한 조회 종료, `{status,message}` 오류 응답, 업로드 크기 제한.
- 두 앱 객체의 동일 IP 예산 공유(리미터 모형), 다른 IP 분리, 신뢰된 프록시 역순 확인, IPv6 정규화, 위조/중복 XFF 거부.
- IP 한도와 전체 한도의 구분, 429 message/Retry-After/scope 헤더, 거절 시 사용자 테이블 증가 없음.
- 엔진 75개 파일의 출처/정규화 SHA-256 검사.
- Python 예상 인원/실험 기록 테스트 **8개**, Node 부하 대상/IP 예산/입력 제한 테스트 **3개**.
- Terraform fmt 문법/형식 검사, Docker Compose config 검사.
- EC2 부팅 템플릿에 포함된 Python 코드 구문 검사. Hibernate MySQL DDL 생성본과 resource 스키마 일치.

## 아직 실행하지 못한 검증

- AWS provider 다운로드가 터미널 네트워크 제한으로 실패해 Terraform validate/plan/apply 미실행.
- Docker 엔진 기본 pipe를 찾지 못해 실제 MySQL/Redis Compose 및 k6 미실행. 실제 Redis 공용 IP 한도·창 회복 probe도 새 저장소 CI에 구성했지만 아직 실행 결과는 없다.
- 신규 B2B S3/CDN의 실제 IAM/CORS·브라우저 PNG 다운로드 미검증.
- 운영 사양의 부하 시험, 수용 인원/비용 검증, 신규 AWS 배포 미실행.
- EC2 AMI 부팅/ASG 예약·교체/RDS 초기화·test destroy의 실환경 검증 미실행. RDS/Redis 정확한 운영 클래스·토폴로지와 T3 credit mode도 미확인이다.
- 중첩 `.github/workflows`는 현 저장소에서 활성화되지 않으며 새 독립 저장소 루트로 이관한 뒤 실행해야 한다.

네트워크 제한 때문에 Gradle은 PC에 존재하는 dependency cache를 읽고 임시 init script로 사본 classpath를 사용했다. javac 종료 시 Windows `AccessDeniedException` 진단이 있었으나 Gradle exit 0, 테스트 결과 XML 실패 0, bootJar 산출을 확인했다. 표준 환경의 CI 검증도 완료해야 한다.

기존 B2C 소스·기본 설정·Dockerfile·Gradle 설정·기존 workflow에는 변경이 없다. 기존 앱 전체 테스트를 새로 돌린 결과로 표현하지 않는다.
