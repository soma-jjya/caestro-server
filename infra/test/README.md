# Caestro 테스트 인프라 (backend + coturn)

ngrok 없이 **백엔드(시그널링 포함) + TURN 서버**를 AWS에 한 번에 띄우는 임시 테스트 스택.
`apply` 한 번으로 두 인스턴스가 올라가고, `destroy` 한 번으로 다 내려간다.

- **backend** (t4g.small): Spring 앱 + MySQL + Redis (모두 Docker), Elastic IP
- **turn** (t4g.nano): coturn (STUN+TURN), Elastic IP
- 도메인/TLS 없음(http/ws), NAT/관리형 DB 없음 → 최저 비용

## 사전 준비
1. AWS 콘솔 EC2 > 키페어에서 **키페어 생성**(.pem 다운로드) → 이름을 `key_name`에 사용
2. `cp terraform.tfvars.example terraform.tfvars` → `turn_password`, `key_name` 채우기

## 1) 인프라 생성
```bash
cd infra/test
terraform init
terraform apply    # 출력: backend_public_ip, turn_public_ip, signaling_url, *_redirect_uri
```
부팅 후 Docker/coturn 자동 설치까지 1~3분.

## 2) 백엔드 앱 배포 (app.jar 업로드)
```bash
# 로컬(맥)에서 JAR 빌드
./gradlew clean build -x test          # build/libs/*.jar 생성

# 인스턴스로 업로드 (BACKEND_EIP = terraform output backend_public_ip)
scp -i <키페어>.pem build/libs/*.jar ubuntu@<BACKEND_EIP>:/opt/app/app.jar

# 인스턴스 접속 후 .env 작성
ssh -i <키페어>.pem ubuntu@<BACKEND_EIP>
cd /opt/app
cp .env.example .env
# .env 편집: KAKAO/GOOGLE CLIENT_ID·SECRET, *_REDIRECT_URI(EIP 반영), JWT_*_SECRET 채우기
docker compose up -d --build           # app + mysql + redis 기동
docker compose logs -f app             # 기동 확인
```

## 3) OAuth redirect 등록
카카오/구글 콘솔에 `terraform output` 의 `kakao_redirect_uri`, `google_redirect_uri` 등록.

## 4) 검증
- Swagger: `http://<BACKEND_EIP>:8080/api-docs`
- 시그널링: `ws://<BACKEND_EIP>:8080/signaling?token=...`
- TURN: Trickle ICE에 `turn:<TURN_EIP>:3478` + turn_user/turn_password → `typ relay` 확인
- 모바일 담당에게 전달: 시그널링 URL + iceServers(STUN 구글 + `turn:<TURN_EIP>:3478`)
  - **저지연 위해 클라이언트에서 `iceTransportPolicy:'relay'` 는 넣지 말 것** (직결 우선 + 필요시 TURN 폴백)

## 5) 테스트 끝나면 (비용 0)
```bash
terraform destroy
```
> ⚠️ Elastic IP는 인스턴스에 붙어있는 동안은 무료지만, **중지(stop)만 하면 과금**됩니다. 임시 테스트이므로 끝나면 **destroy**를 권장.

## 비용(서울, 프리티어 아님, 24h 기준 근사치)
- t4g.small ~$12/월 + t4g.nano ~$4/월 + EBS(16+8GB) ~$2/월 = 약 **$18/월**
- 테스트할 때만 켜고 destroy 하면 사실상 몇 달러 이하. egress는 테스트분이라 무료 한도(월 100GB) 내.
