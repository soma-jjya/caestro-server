# B2B 용량 실험 기록

새 독립 저장소 기준 `load-test/results/`이다. 아직 AWS 실측 결과는 없다.

- `participants.json`: k6의 가장 최근 참여 시험 원본. 매번 덮어쓰므로 다음 실험 전에 기록기로 보관한다.
- `shared-ip.json`: 같은 환경의 공용 IP 한도·위조 XFF 거부·갱신·회복 시험 원본.
- `<experiment-id>.json`: 설정, 실제 측정값, 원본 요약과 SHA-256을 담은 보관 기록. 같은 ID를 덮어쓰지 않는다.

`experiment.example.json`을 복사하고 null을 **확인한 실제 사양**으로 채운다. `observations.example.json`에는 CloudWatch/Prometheus에서 같은 시험 시간 구간에 측정한 수치를 넣는다. 숫자는 추측해 채우지 않는다. CPU 크레딧과 전체 노드별 메모리, DB 연결/IO, Redis 지표를 같이 확인한다. 원시 CloudWatch export는 별도로 보관하고 검토 자료로 연결한다.

```text
python tools/record_experiment.py --id yuja-baseline-001 --experiment experiment.json --journey load-test/results/participants.json --nat load-test/results/shared-ip.json --observations observations.json
python tools/record_experiment.py --id yuja-four-instances-002 --experiment experiment-002.json --journey load-test/results/participants.json --nat load-test/results/shared-ip.json --observations observations-002.json --previous load-test/results/yuja-baseline-001.json
```

첫 실험은 `t3.micro` 2대. 다음 실험은 **설정 한 필드만** 바꾸고 트래픽·기간·사진 크기·최대 NAT 인원은 유지한다. 사진/이미지 digest/AMI/한도를 바꿨다면 그것 자체를 별도 실험으로 기록한다. 새 트래픽 조건은 새 baseline 연속 실험으로 시작한다.

`BLOCKED_GUEST_POLICY`는 증설 후보를 뜻하지 않는다. 공용 Wi-Fi의 정상 신규 게스트 수가 IP 한도에 걸리는지 정책을 먼저 검토한다. IP별 429와 전체 발급 한도의 429를 서버 지연·5xx와 분리한다. NAT 계약 시험이 통과했다는 것은 31번째가 제대로 거부됐다는 의미이며, 실제 행사에서 31번째 정상 사용자가 참여할 수 있다는 뜻이 아니다.

`CANDIDATE_FOR_REVIEW`도 자동 운영 승격이 아니다. 관측치와 크레딧 지속성, 브라우저 완성 다운로드, 운영 사양 일치, 다중 부하 발생기의 전체 합산 증거를 사람이 확인한다. 이 기록기는 한 발생기의 결과를 기록하므로 분산 시험의 각 발생기 기록만으로 전체 축제 합격을 주장하지 않는다.

실제 결과 파일은 기본 Git 제외다. 토큰·개인 사진·Terraform state는 저장하지 않는다. 검토할 수 있는 보관소/CI artifact에 결과를 남긴 뒤 스테이징을 정리한다.
