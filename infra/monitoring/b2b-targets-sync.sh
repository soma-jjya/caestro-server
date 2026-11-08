#!/bin/bash
# 모니터링 EC2 에서 1분마다(cron) 돈다. B2B 축제 대상 목록을 S3 에서 받아 b2b_targets.json 을 갈아 끼운다 (#241).
# 목록은 축제 서버 워크플로(peakpic-b2b-server festival.yml)가 서버를 만들 때 갱신한다. Prometheus file_sd 가 바뀐 파일을 알아서 다시 읽는다.
# 받기 실패나 형식 오류면 기존 파일을 그대로 둔다(수집이 끊기지 않게).
set -u
DIR="${MONITORING_DIR:-$HOME/monitoring}"
SOURCE="${B2B_TARGETS_S3:-s3://peakpic-b2b-terraform-state/monitoring/b2b_targets.json}"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
aws s3 cp --only-show-errors "$SOURCE" "$TMP" || exit 0
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert isinstance(d,list) and all(isinstance(e.get("targets"),list) for e in d)' "$TMP" || exit 0
cmp -s "$TMP" "$DIR/b2b_targets.json" && exit 0
chmod 644 "$TMP" && mv "$TMP" "$DIR/b2b_targets.json"
