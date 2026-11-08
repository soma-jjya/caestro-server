# 모니터링 스택 (Prometheus + Grafana)

B2B 대시보드: [peakpic-b2b.json](dashboards/peakpic-b2b.json). 지표의 측정 범위와 부하 검증 절차는 [B2B 실행 안내](../../load-test/B2B.md)를 참고한다.

앱(#80)이 노출하는 `/actuator/prometheus` 지표를 수집·시각화한다.
모니터링 EC2 1대(ASG 밖, 프라이빗 서브넷, **인바운드 0**)에서 docker compose로 운영한다.

## 구성

```
[ASG 인스턴스들:8080] ←─ 15s 스크레이프(EC2 SD로 자동 발견 + 정적 토큰) ── [모니터링 EC2]
                                                                        Prometheus:9090
                                                                        Grafana:3000
사용자 접속: SSM 포트 포워딩 (퍼블릭 경로 없음)
```

## 최초 설치 (콘솔 + SSM)

1. **IAM 역할** `peakpic-monitoring-role` 생성: `AmazonSSMManagedInstanceCore` +
   인라인 정책(EC2 SD용):
   ```json
   { "Version": "2012-10-17",
     "Statement": [{ "Effect": "Allow", "Action": ["ec2:DescribeInstances", "ec2:DescribeAvailabilityZones"], "Resource": "*" }] }
   ```
2. **EC2 생성**: t4g.micro(ARM), Amazon Linux 2023, **프라이빗 서브넷**, 위 역할 연결,
   보안 그룹 `peakpic-monitoring-sg` — **인바운드 없음**, 아웃바운드 전체
3. **백엔드 SG 수정**: 인바운드 추가 `8080 ← peakpic-monitoring-sg` (스크레이프 허용)
4. **모니터링 EC2에 SSM 접속** 후:
   ```bash
   sudo dnf install -y docker && sudo systemctl enable --now docker
   sudo curl -L https://github.com/docker/compose/releases/latest/download/docker-compose-linux-aarch64 \
     -o /usr/local/bin/docker-compose && sudo chmod +x /usr/local/bin/docker-compose
   mkdir ~/monitoring && cd ~/monitoring
   # prometheus.yml, docker-compose.yml 를 이 디렉터리로 복사(레포의 infra/monitoring/)
   printf '%s' '<metrics.token 값>' > metrics_token && chmod 600 metrics_token
   sudo docker-compose up -d
   ```

## 접속 (SSM 포트 포워딩 — 로컬에서)

```bash
# Grafana
aws ssm start-session --profile peakpic --region ap-northeast-2 \
  --target <모니터링 인스턴스ID> \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["3000"],"localPortNumber":["3000"]}'
# → 브라우저 http://localhost:3000 (초기 admin/admin, 접속 후 변경)

# Prometheus 타깃 확인은 portNumber 9090으로 동일하게 → http://localhost:9090/targets
```

## Grafana 초기 설정

1. Data source → Prometheus → URL `http://prometheus:9090`
2. 대시보드 패널 (PromQL):
   - 인스턴스별 연결 수: `ws_connections_active`
   - **크로스 인스턴스 중계 비율**: `sum(rate(ws_relay_sent_total{route="pubsub"}[5m])) / sum(rate(ws_relay_sent_total[5m]))`
   - JOIN 결과 분포: `increase(ws_join_total[1h])` (result 라벨별)
   - Pub/Sub 유실: `increase(ws_relay_no_receiver_total[5m])` (0이어야 정상)
   - 리퍼 정리량: `increase(ws_reaper_closed_total[10m])`
   - JVM 힙: `jvm_memory_used_bytes{area="heap"}` vs `jvm_memory_max_bytes{area="heap"}` (-m 800m 검증)
   - Hikari: `hikaricp_connections_active`
   - 메시지 처리시간 P95(타입별): `histogram_quantile(0.95, sum by (le, type) (rate(ws_message_handle_seconds_bucket[5m])))`
   - Redis 명령 지연 P95(명령별): `histogram_quantile(0.95, sum by (le, command) (rate(lettuce_command_completion_seconds_bucket[5m])))`
   - WS 종료 코드 분포: `sum by (code) (increase(ws_close_total[1m]))` (1000=클라 정상, 1006=비정상, 1012=드레인 정돈 종료)
   - 드레인이 닫은 소켓 수(배포 1회당 영향 규모): `increase(ws_drain_closed_total[10m])`
3. 대시보드 완성 후 JSON 내보내기 → `infra/monitoring/dashboards/`에 백업

## 주의

- `metrics_token` 파일은 커밋 금지(.gitignore 처리). 앱 쪽 값은 dev=application-dev.yml,
  prod=APPLICATION_PROD_YML 시크릿의 `metrics.token`
- 새 지표를 추가하면 별도 조치 없이 자동 수집된다 (pull 모델)

## B2B 축제 전용 서버 지표 (#236, #241)

축제마다 따로 띄운 B2B 서버(peakpic-b2b-server)는 별도 VPC 라 EC2 디스커버리로 찾지 못한다. 잡 `peakpic-b2b-festival` 이 공개 ALB 를 거쳐 긁는다.
- **대상**: `b2b_targets.json`(file_sd). 축제 서버 워크플로가 서버를 만들 때 S3(`peakpic-b2b-terraform-state/monitoring/b2b_targets.json`)를 갱신한다. `b2b-targets-sync.sh` 가 1분마다 받아 갈아 끼운다
- **키**: `b2b_admin_key` 파일에 모든 축제 공통 **계측 키** 하나(축제 서버의 `/actuator/prometheus` 에서만 통한다). 축제를 추가·교체해도 손댈 것이 없다

두 파일이 없으면 Prometheus 설정 검사가 실패해 기동하지 않는다. 처음 한 번은 파일을 먼저 만들고 설정을 갱신한다.

### 한 번만: 설치

```bash
# 1) (CloudShell) 모니터링 EC2 역할에 대상 목록 읽기 권한
ROLE=$(aws ec2 describe-instances --instance-ids <모니터링 인스턴스ID> --query 'Reservations[0].Instances[0].IamInstanceProfile.Arn' --output text | sed 's|.*/||' | xargs -I{} aws iam get-instance-profile --instance-profile-name {} --query 'InstanceProfile.Roles[0].RoleName' --output text)
aws iam put-role-policy --role-name "$ROLE" --policy-name b2b-targets-read --policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:GetObject","Resource":"arn:aws:s3:::peakpic-b2b-terraform-state/monitoring/b2b_targets.json"}]}'
# 계측 키 확인(화면에 한 번만): Secrets Manager peakpic-b2b/prod-<아무 축제> 의 metricsKey

# 2) (모니터링 EC2, SSM) 키 파일·동기화 스크립트·cron
cd ~/monitoring
printf '%s' '<metricsKey>' > b2b_admin_key && sudo chown 65534:65534 b2b_admin_key && chmod 600 b2b_admin_key   # Prometheus 는 nobody 로 읽는다
curl -fsSO https://raw.githubusercontent.com/soma-jjya/caestro-server/main/infra/monitoring/b2b-targets-sync.sh && chmod +x b2b-targets-sync.sh
./b2b-targets-sync.sh && cat b2b_targets.json
command -v crontab >/dev/null || { sudo dnf install -y -q cronie && sudo systemctl enable --now crond; }   # AL2023 에는 cron 이 없다
( crontab -l 2>/dev/null | grep -v b2b-targets-sync; echo "* * * * * $HOME/monitoring/b2b-targets-sync.sh" ) | crontab -
sudo docker-compose restart prometheus   # 키를 바꿨을 때만
```

확인: Prometheus `/targets` 에서 `peakpic-b2b-festival` 이 축제 수만큼 UP, `jvm_memory_used_bytes{job="peakpic-b2b-festival"}` 에 `slug` 라벨.
