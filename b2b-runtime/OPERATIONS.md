# 독립 B2B 운영·용량 검증 순서

현재는 독립 참가 서버와 Terraform/시험 도구를 구현한 단계다. AWS 배포와 실측은 아직 하지 않았다. 중앙 관리자에서 입력 한 번으로 아래 단계를 실행하는 작업 큐·조정기도 후속 작업이다.

## 기존 B2C와의 경계

기존 B2C EC2/ASG/RDS/Redis/S3/CloudFront, 배포 workflow, `STUDIO_KEY`, 배포 역할은 변경하지 않는다. 새 `peakpic-b2b-server` 저장소에 이 디렉터리만 옮긴다. 기존 caestro-server main에 올려 릴리스하지 않는다. 중첩 workflow는 이관 전에는 실행되지 않는다.

B2B 공용 S3/CDN/ECR은 `infra/storage`, 축제별 EC2 ASG/ALB/전용 MySQL/전용 Redis는 `infra/festival`에서 관리한다. 두 구성의 state를 분리하고 test/prod/project/runtime별로도 state를 분리한다. Terraform에 기존 B2C 리소스 import나 수정 선언은 없다. 같은 계정을 쓴다면 계정의 서비스 한도는 여전히 공유한다.

`peakpic.app/go/{slug}/{code}`는 FE 경로다. slug 생성은 DNS 도메인 생성이 아니다. 새 B2B 참가 API는 `/projects/{slug}/...`이고 프로젝트별 API origin/중앙 라우팅 레지스트리는 별도 연결해야 한다. 현재 운영 yuja는 자동 전환하지 않는다.

## 1. 운영과 같은 출발점 준비

첫 실험은 **t3.micro 2대**다. RDS·Redis는 운영의 작은 사양을 확인해서 그대로 입력한다. 확인되지 않은 클래스를 임의로 더 크게 잡지 않는다.

필수 입력은 `infra/festival/variables.tf`를 기준으로 한다.

- B2B account/region/AZ 2개, projectId/slug/runtimeId, test 또는 prod, 별도 API origin/인증서, 행사 및 보관 기한.
- B2B 버킷/CDN, ECR의 고정 이미지 digest, 해당 축제 Secrets Manager ARN.
- 소유 계정의 고정 x86_64 AL2023 AMI, T3 credit mode, 컨테이너 메모리 한도, JVM 설정, 프로세스별 DB 연결 풀.
- RDS 클래스·MySQL 버전·Single/Multi-AZ·gp2/gp3·저장 용량 및 상한.
- Redis 노드 타입·버전·1/2개·Multi-AZ 여부.
- IP당 발급 한도/창 길이와 축제 런타임 전체 발급 한도.

AMI에는 Docker, AWS CLI, Python3, SSM Agent, CloudWatch Agent를 미리 설치·검증한다. 새 B2B 빌더에서 만들고 AMI ID를 고정한다. 기존 B2C 서버를 변경하거나 이미지를 뜨는 과정은 포함하지 않는다. 부팅할 때 임의 최신 패키지를 설치하지 않는다. Dockerfile JVM은 MaxRAMPercentage=60이므로 실제 컨테이너/호스트 메모리 여유를 함께 기록한다.

Secrets Manager `peakpic-b2b/...`의 JSON 필드는 `jwtSecret`, `adminKey`, `dbPassword`다. 각기 독립적인 무작위 값을 사용한다. EC2가 자기 Secret을 읽어 root 전용 `/run` 환경 파일을 만든다. user-data에는 비밀 값이 들어가지 않는다. DB 암호는 Terraform state에 들어가므로 암호화·잠금·접근 제한이 필수이며 state/plan을 공개 artifact로 올리지 않는다. 고객 관리 KMS 키를 쓰면 해당 키의 제한된 복호화 권한도 별도 구성한다.

B2B 전용 계정/역할로 storage를 준비하고 독립 저장소의 이미지 게시 workflow를 실행한다. 이 workflow는 B2B ECR에 이미지만 올린다. 기존 앱이나 새 ASG를 자동 배포하지 않는다.

## 2. 새 스테이징 초기화

1. test 전용 backend key와 입력 파일을 사용해 init → validate → plan을 확인한다. `instance_type=t3.micro`, `instance_count=2`, 예약 증설은 끈다.
2. `enable_service=false`, `enable_database_initializer=true`로 준비한다. 별도 초기화용 EC2 한 대가 **빈 B2B DB에만** 스키마를 만든다. SSM에서 `systemctl show peakpic-b2b -p Result -p ExecMainStatus`, journal/CloudWatch 로그로 종료 코드 0과 초기화 완료를 확인한다.
3. `enable_database_initializer=false`, `enable_service=true`로 변경한다. 초기화용 EC2는 정리하고 ASG의 앱 2대를 켠다. 기존 테이블이 있는 DB에 initializer를 다시 켜지 않는다. 실패한 MySQL DDL은 일부만 반영될 수 있어 자동 성공 처리하지 않는다.
4. DNS/인증서를 연결하고 ALB healthy target 2개, 모든 노드의 readiness, systemd 및 이미지 digest, 연결된 DB/Redis를 확인한다. `apply` 완료만으로 참여 가능하다고 판정하지 않는다.
5. 시험 프로젝트에 합성 프레임/사진을 준비해 공개한다. AWS 시험은 실제 B2B S3 저장 경로를 사용한다. CI Compose의 RDS 이미지 저장은 기능 시험용이다.

운영을 준비할 때는 빈 고정 ASG에서 예약 ASG로 바꾸는 작업을 서비스 최초 활성화와 함께 한다. 실행 중인 환경에서 `enable_scheduled_scaling` 모드를 바꾸면 ASG 교체가 발생하므로 기존 축제에 즉흥적으로 적용하지 않는다.

## 3. 게스트 발급 IP 한도를 먼저 확인

B2B 기본값은 **한 IP당 60초에 30회**, 별도로 런타임 전체 기본 300회/분이다. 실제 AWS 값은 명시적으로 입력한다. 전용 Redis에 집계하므로 앱 서버 2→4대로 늘려도 같은 IP의 한도가 배로 늘지 않는다. IP 한도 통과 후 전체 한도를 검사하며, 전체 한도로 거절된 시도도 IP 시도 수에는 포함된다.

ALB는 X-Forwarded-For에 접속 IP를 추가하고 앱은 TCP peer부터 신뢰된 프록시만 오른쪽에서 역순으로 확인한다. 현재 신뢰 범위는 새 ALB subnet뿐이며 앱 8080은 ALB SG에서만 접근 가능하다. Docker host network를 사용해 peer를 보존한다. 임의의 XFF 첫 값을 실제 사용자로 신뢰하지 않는다.

**Cloudflare/중앙 프록시를 앞에 둔다면 이 구성만으로 실제 방문자 IP가 보장되지 않는다.** 현재는 신뢰하지 않는 마지막 프록시 IP까지 식별한다. FE가 실제로 쓰는 전체 경로에서 공용 IP 묶임을 재검증하고, 추가 프록시 인증·출처 제한·신뢰 범위를 설계한 뒤 사용한다. 헤더만 바꿔 서로 다른 사용자 IP를 만들어 부하 시험을 통과시키지 않는다.

공용 Wi-Fi뿐 아니라 이동통신 NAT도 예상한다. `capacity-request.example.json`의 `peakNewGuestsPerIpPerMinute`에는 가장 붐비는 공인 IP 하나에서의 신규 게스트 수를 넣는다. 총 참여 인원으로 이를 자동 추정할 수는 없다. FE의 토큰 재사용/single-flight 갱신도 유지한다.

```text
python tools/capacity_plan.py capacity-request.example.json
k6 run -e TARGET_ORIGIN=https://<TEST_HOST> -e CONFIRM_TEST_ORIGIN=https://<TEST_HOST> -e PROJECT_SLUG=<SLUG> load/shared-ip.js
```

키는 `B2B_ADMIN_KEY` 환경 변수로만 전달한다. 이 probe는 조용한 test 환경에서 먼저 기존 창이 끝나길 기다린다. 같은 실제 출구 IP로 한도까지 성공, 이후 5회 429, 위조 XFF로 우회 불가, 기존 토큰 갱신 성공, 창 만료 후 발급 회복을 검사한다. 기본 30/60과 비슷한 범위의 한도만 자동 probe하며 더 큰 정책은 별도 시험 계획이 필요하다.

429는 기존 `{status,message}`에 `Retry-After`, `X-RateLimit-Scope: guest-ip|guest-runtime`을 제공한다. 지표 `b2b_guest_rate_limited_total`의 scope도 ip/runtime으로 구분한다. Redis 장애는 허용으로 우회하지 않는다.

**NAT probe 통과는 행사 수용 가능 판정이 아니다.** 정상 31번째 사람도 막혔다면 행사 운영 정책 검토가 필요하다. `POLICY_REVIEW_REQUIRED`를 서버 증설로 해결하려 하지 않는다. B2B IP 한도를 바꾸면 별도 실험으로 기록하고 어뷰즈 방어와 정상 참가자 거절률을 함께 재검증한다. B2C 한도는 그대로다.

## 4. 기대 트래픽 → 한 항목 변경 → 재시험

`capacity_plan.py`가 기본 여유율 1.5를 적용한 도착률/VU 입력을 계산한다. 이것은 실측 용량 보장이 아니다. 사용자당 네컷 1개/사진 4개가 기본 모델이며 실제 FE 행동·사진 크기로 맞춘다. 처음에는 baseline을 그대로 시험하고 통과하면 증설하지 않는다.

```text
k6 run -e TARGET_ORIGIN=https://<TEST_HOST> -e CONFIRM_TEST_ORIGIN=https://<TEST_HOST> -e PROJECT_SLUG=<SLUG> -e PROFILE=capacity -e ARRIVALS_PER_MINUTE=<RATE_PER_RUNNER> -e MAX_VUS=<VUS> -e DURATION_MINUTES=60 load/participants.js
```

스크립트는 확인된 test 환경만 허용한다. 한 발생기의 요청량이 IP 한도를 넘으면 capacity 시험을 시작하지 않는다. 필요한 경우 실제로 서로 다른 공인 IP를 쓰는 부하 발생기를 구성하고 총 도착률과 결과를 합산한다. 발생기 3개가 같은 NAT 뒤에 있으면 IP 3개가 아니다. quota를 몰래 올리거나 도착률을 낮춰 원래 기대 트래픽이 통과했다고 보고하지 않는다.

기본 k6 실행은 10분이며 예시 60분 역시 수용 인원 보장을 뜻하지 않는다. T3는 CPU 크레딧 상태에 따라 결과가 달라진다. 시험 전후 CPUCreditBalance, CPUSurplusCreditBalance/Charged, 지속 부하에서의 CPU·응답 시간, 호스트/컨테이너 메모리와 OOM을 확인한다. 새 서버의 충전된 크레딧만 소모하고 끝난 짧은 시험으로 긴 축제를 보장하지 않는다.

변경 순서:

1. 앱이 병목이면 `instance_count=2 → 4`, 다른 설정 고정, 재시험.
2. 그래도 앱 CPU/메모리가 병목이면 `instance_type=t3.small`, 재시험. 컨테이너 메모리 한도 상향은 자동으로 따라가지 않으며 필요하면 별도 실험으로 측정한다.
3. DB CPU/IO/연결이 병목이면 RDS를 한 단계 올리고 재시험. 앱 수 × 프로세스별 연결 풀도 확인한다. RDS 저장 용량은 올린 뒤 같은 인스턴스에서 줄일 수 없으므로 복귀 계획과 구분한다.
4. Redis 병목·게스트 한도·발생기 누락 iteration도 각각 원인으로 구분한다. 여러 설정을 한꺼번에 바꾸지 않는다.

[load-test/results/README.md](load-test/results/README.md)의 기록기로 설정·원본 k6 결과·측정 숫자를 매 실험마다 남긴다. IP 429, 전체 한도 429, 지연/5xx, 미완료/누락, DB·Redis·메모리·크레딧을 구분한다. 합격 근거 없이 자동 승격하지 않는다. CDN 로드→캔버스 합성→PNG 다운로드는 실제 브라우저에서도 검증한다.

## 5. 검증된 사양을 B2B 운영에 반영

운영은 test와 별도 DB/Redis/runtimeId/prefix/state를 쓴다. 이미지 digest와 검증된 사양을 맞추고 test 데이터를 복사하지 않는다. 기존 B2C 운영 설정은 건드리지 않는다.

`enable_scheduled_scaling=true`인 새 B2B 운영 ASG에는 두 예약이 생긴다.

- 시작: 행사 시작 15분 전(변수로 조정) 검증한 `instance_count`로 min/max/desired를 모두 설정.
- 종료: `ends_at`에 min/max/desired를 모두 2로 복귀. 다운로드 보관 기간이 있으므로 0으로 내리지 않음.

시각은 Terraform 입력에 UTC `Z`로 전달한다. 시작 예약이 이미 지난 뒤 생성하지 않는다. 예약은 지연될 수 있으므로 시작 전 실제 healthy 수를 확인한다. 스테이징 고정 ASG에는 CPU 자동 증설을 두지 않는다. 운영 예약 ASG의 수량은 예약이 소유하므로 Terraform은 min/max/desired drift를 되돌리지 않는다. 행사 중 숫자를 긴급 조정할 때는 이 점과 아직 남은 예약을 함께 확인한다.

인스턴스 타입/메모리/이미지 변경은 launch template과 rolling refresh에 반영된다. RDS 클래스 변경은 즉시 보장되지 않고 유지보수 적용이 필요할 수 있다. 전날 사람이 계획된 작업으로 적용하고 실제 노드 교체/DB pending modifications 해소·health를 확인한다. 행사가 끝나면 사양 복귀도 사람이 수행하고, ASG 종료 예약이 타입/DB까지 내려 준다고 생각하지 않는다.

## 6. 스테이징 정리

먼저 결과와 원시 지표를 보관한다. **해당 test backend key, environment=test, projectId/runtimeId, object prefix**를 확인하고 그 환경의 destroy plan을 검토한 뒤 적용한다. AWS 부하 시험/삭제는 현재 실행하지 않았다.

`terraform output -json runtime`에서 위 식별자를 확인한다. shared/storage와 prod state에는 destroy를 실행하지 않는다. test의 ALB/RDS 삭제 방지는 꺼져 있고 최종 DB snapshot을 만들지 않아 테스트 ASG/ALB/RDS/Redis/VPC를 정리할 수 있다. prod ALB/RDS는 삭제 방지를 유지한다.

공용 S3는 festival state 밖에 있으므로 test stack destroy만으로 사진이 삭제되지 않는다. 보관한 결과의 정확한 `b2b/test/{projectId}/{runtimeId}/` prefix만 따로 정리하고 확인한다. 버킷/다른 prefix/실제 축제 사진은 지우지 않는다. 공용 S3/CDN/ECR에는 prevent_destroy를 유지한다. 운영 retention 종료 후 이미지/DB/스냅샷 정리는 아직 별도 조정기를 연결해야 한다.

알려진 CDN URL은 S3 객체를 실제 삭제하기 전까지 읽을 수 있다. API의 보관 종료 410과 물리 삭제는 별개다. 기존 공유 링크를 유지하는 동안 서버·DB를 제거하지 않는다.

## 후속 연결

중앙 관리 API/영속 작업 큐, B2B 전용 배포 worker, 사양·예상 트래픽·공용 IP 가정 입력, 실제 지표 자동 수집, 분산 발생기 결과 집계, slug 라우팅, retention 정리를 연결해야 입력부터 종료까지 자동화된다. 현재 도구가 이 전체를 완료했다고 표현하지 않는다.

근거: [ASG 예약 증설](https://docs.aws.amazon.com/autoscaling/ec2/userguide/ec2-auto-scaling-scheduled-scaling.html), [T3 크레딧](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/burstable-credits-baseline-concepts.html), [ALB 전달 IP 헤더](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/x-forwarded-headers.html), [CloudWatch Agent 설정](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch-Agent-Configuration-File-Details.html).