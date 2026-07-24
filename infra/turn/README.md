# TURN 서버 (coturn) — Terraform

WebRTC의 다른 네트워크(LTE ↔ Wi-Fi) 간 P2P 연결을 위한 임시 TURN 서버를 AWS에 최소 비용으로 배포한다.
`apply` 한 번으로 EC2(t4g.nano) 생성 + coturn 설치·설정까지 자동 완료된다.

## 사용법

```bash
cd infra/turn
cp terraform.tfvars.example terraform.tfvars   # turn_password 등 채우기
terraform init
terraform apply                                # 출력된 turn_public_ip / turn_url 확인
```

부팅 후 coturn 자동 구성까지 1~2분 걸린다.

## 검증
[Trickle ICE](https://webrtc.github.io/samples/src/content/peerconnection/trickle-ice/)에 출력값(`turn_url`, `turn_user`, `turn_password`) 입력 →
Gather candidates → `typ relay` 후보가 뜨면 정상.

## 비용 (프리티어 아님 기준, 서울)
- t4g.nano: 약 $3~4/월 (상시 실행 시)
- EBS 8GB gp3: 약 $0.6/월
- 데이터 전송 out: 월 100GB 무료, 초과분 ~$0.12/GB (테스트론 무료 한도 내)
- Elastic IP/NAT 미사용 → 추가 비용 없음

## 비용 최소화
- **테스트 안 할 때 중지**: `aws ec2 stop-instances --instance-ids <id>` (컴퓨팅 0, EBS만 소액)
- **완전 삭제(권장, 임시라면)**: `terraform destroy` → 월 $0
- AWS Budgets에서 billing 알람($5) 설정 권장

## 주의
- 자동 공인 IP를 쓰므로 **인스턴스 재시작 시 IP가 바뀐다** (클라이언트 iceServers 주소도 갱신 필요). 고정이 필요하면 Elastic IP를 추가하되 중지 중 소액 과금됨.
- `terraform.tfvars` (비밀번호 포함)는 커밋하지 말 것.
