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

## B2B 축제 전용 서버 지표 (#236)

축제마다 따로 띄운 B2B 서버(peakpic-b2b-server)는 별도 VPC 라 EC2 디스커버리로 찾지 못한다. 잡 `peakpic-b2b-festival` 이 공개 ALB 를 거쳐 그 축제의 운영자 키로 긁는다.
대상과 키는 모니터링 EC2 의 `~/monitoring/` 에 파일로 두고 커밋하지 않는다(`.gitignore`).

**두 파일이 없으면 Prometheus 설정 검사가 실패해 기동하지 않는다.** 그러니 `prometheus.yml`·`docker-compose.yml` 을 갱신하기 전에 먼저 만든다.

```bash
cd ~/monitoring
# 축제 서버 대상: 주소와 경로. slug 는 축제 주소 이름
printf '[{"targets":["<slug>.<축제 API 도메인>"],"labels":{"__metrics_path__":"/projects/<slug>/actuator/prometheus","slug":"<slug>"}}]' > b2b_targets.json
# 운영자 키: CloudShell 에서 Secrets Manager peakpic-b2b/prod-<slug> 의 adminKey 를 확인해 붙여 넣는다(개행 없이)
printf '%s' '<adminKey>' > b2b_admin_key && chmod 600 b2b_admin_key
sudo docker-compose up -d --force-recreate prometheus
```

확인: Prometheus `/targets` 에서 `peakpic-b2b-festival` UP, `jvm_memory_used_bytes{job="peakpic-b2b-festival"}`.
축제를 바꾸면 두 파일만 고친다(대상 파일은 자동으로 다시 읽고, 키를 바꿨으면 prometheus 를 재시작한다). 축제가 없을 때는 대상 파일을 `[]` 로 두고 키 파일은 아무 값이나 남긴다.

