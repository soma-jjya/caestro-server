# 모니터링 스택 (Prometheus + Grafana)

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
3. 대시보드 완성 후 JSON 내보내기 → `infra/monitoring/dashboards/`에 백업

## 주의

- `metrics_token` 파일은 커밋 금지(.gitignore 처리). 앱 쪽 값은 dev=application-dev.yml,
  prod=APPLICATION_PROD_YML 시크릿의 `metrics.token`
- 새 지표를 추가하면 별도 조치 없이 자동 수집된다 (pull 모델)
