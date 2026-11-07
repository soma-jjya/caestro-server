"""모델이 부를 수 있는 조사 도구 4종. 전부 읽기 전용이며, 결과는 dict 로 돌려준다(JSON 직렬화 가능).

    query_logs         CloudWatch Logs Insights — 앱 로그(ERROR/WARN 집계, 최근 에러, 분당 건수, 패턴 검색)
    query_metrics      CloudWatch 지표 — ALB·EC2·ElastiCache·RDS 미리 정한 지표만
    query_access_logs  S3 의 ALB 액세스 로그 — 발신 IP·UA·경로·상태코드 집계
    recent_deploys     ASG instance refresh·활동 기록 + GitHub 최근 커밋(공개 레포, 토큰 불필요)

LLM 없이도 단독으로 호출·테스트할 수 있어야 한다. 모델에 넘기는 스키마는 TOOL_SPECS.
"""

import gzip
import json
import os
import re
import shlex
import time
import urllib.request
from collections import Counter
from datetime import datetime, timedelta, timezone

WINDOW_BEFORE_MIN = 5   # 알람 시각 앞 5분
WINDOW_AFTER_MIN = 5    # 알람 시각 뒤 5분 (알람은 보통 조건 충족 뒤 몇 분 지나 울린다)
MAX_WINDOW_MIN = 24 * 60

# 모델이 고를 수 있는 지표. 임의 지표를 못 부르게 이름 → (namespace, metric, stat, dims 키) 로 고정한다
METRIC_SPECS = {
    "alb_requests": ("AWS/ApplicationELB", "RequestCount", "Sum", ("LoadBalancer",)),
    "alb_5xx": ("AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", "Sum", ("LoadBalancer",)),
    "alb_4xx": ("AWS/ApplicationELB", "HTTPCode_Target_4XX_Count", "Sum", ("LoadBalancer",)),
    "alb_elb_5xx": ("AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count", "Sum", ("LoadBalancer",)),
    "alb_latency_p99": ("AWS/ApplicationELB", "TargetResponseTime", "p99", ("LoadBalancer",)),
    "alb_unhealthy_hosts": ("AWS/ApplicationELB", "UnHealthyHostCount", "Maximum", ("LoadBalancer", "TargetGroup")),
    "alb_healthy_hosts": ("AWS/ApplicationELB", "HealthyHostCount", "Minimum", ("LoadBalancer", "TargetGroup")),
    "ec2_cpu": ("AWS/EC2", "CPUUtilization", "Average", ("AutoScalingGroupName",)),
    "ec2_cpu_credit": ("AWS/EC2", "CPUCreditBalance", "Minimum", ("AutoScalingGroupName",)),
    "ec2_status_check_failed": ("AWS/EC2", "StatusCheckFailed", "Maximum", ("AutoScalingGroupName",)),
    "redis_connections": ("AWS/ElastiCache", "CurrConnections", "Maximum", ("CacheClusterId",)),
    "redis_memory_pct": ("AWS/ElastiCache", "DatabaseMemoryUsagePercentage", "Maximum", ("CacheClusterId",)),
    "redis_evictions": ("AWS/ElastiCache", "Evictions", "Sum", ("CacheClusterId",)),
    "rds_free_storage_bytes": ("AWS/RDS", "FreeStorageSpace", "Minimum", ("DBInstanceIdentifier",)),
    "rds_cpu": ("AWS/RDS", "CPUUtilization", "Average", ("DBInstanceIdentifier",)),
}

# Logs Insights 질의. 운영 로그는 LogstashEncoder JSON 이라 level·logger_name·message·stack_trace 필드가 바로 잡힌다
LOG_QUERIES = {
    "summary": (
        'filter level in ["ERROR","WARN"]'
        " | stats count() as n by level, logger_name"
        " | sort n desc | limit 20"
    ),
    "errors": (
        'filter level = "ERROR"'
        " | fields @timestamp, logger_name, message, stack_trace"
        " | sort @timestamp desc | limit 15"
    ),
    "volume": "stats count() as n, sum(level = \"ERROR\") as errors by bin(1m) | sort @timestamp asc | limit 60",
    "search": (
        "filter message like /{pattern}/ or stack_trace like /{pattern}/"
        " | fields @timestamp, level, logger_name, message"
        " | sort @timestamp desc | limit 20"
    ),
}

TOOL_SPECS = [
    {
        "name": "query_logs",
        "description": (
            "앱 서버 로그(CloudWatch Logs Insights)를 알람 창 안에서 조회한다. "
            "kind=summary: ERROR/WARN 을 레벨·로거별 건수로 집계. kind=errors: 최근 ERROR 15건(메시지·스택 첫 줄). "
            "kind=volume: 분당 전체 로그 수와 ERROR 수. kind=search: pattern(정규식)으로 메시지·스택 검색."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "kind": {"type": "string", "enum": ["summary", "errors", "volume", "search"]},
                "pattern": {"type": "string", "description": "kind=search 일 때 정규식. 그 외에는 빈 문자열"},
                "window_minutes": {
                    "type": "integer",
                    "description": "알람 시각 앞으로 몇 분을 볼지(기본 5, 최대 1440). 뒤로는 항상 5분",
                },
            },
            "required": ["kind", "pattern", "window_minutes"],
            "additionalProperties": False,
        },
        "strict": True,
    },
    {
        "name": "query_metrics",
        "description": (
            "CloudWatch 지표 시계열을 돌려준다. 지표 이름은 목록 중 하나: " + ", ".join(METRIC_SPECS)
            + ". 결과는 [시각, 값] 목록과 min/max/sum/last. 배포 여부 판단에는 recent_deploys 를 쓸 것."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "metric": {"type": "string", "enum": sorted(METRIC_SPECS)},
                "window_minutes": {"type": "integer", "description": "알람 시각 앞으로 몇 분(기본 30, 최대 1440)"},
            },
            "required": ["metric", "window_minutes"],
            "additionalProperties": False,
        },
        "strict": True,
    },
    {
        "name": "query_access_logs",
        "description": (
            "ALB 액세스 로그(S3)를 알람 창 안에서 읽어 집계한다: 총 요청 수, 상태코드별 수, 발신 IP 상위, "
            "User-Agent 상위, 요청 경로 상위. status_min 으로 특정 상태코드 이상만 볼 수 있다(예: 500). "
            "누가 보냈는지(스캐너·특정 IP·앱 종류)는 이 도구로만 알 수 있다."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "status_min": {"type": "integer", "description": "이 값 이상 상태코드만 집계. 전체는 0"},
                "window_minutes": {"type": "integer", "description": "알람 시각 앞으로 몇 분(기본 5, 최대 60)"},
            },
            "required": ["status_min", "window_minutes"],
            "additionalProperties": False,
        },
        "strict": True,
    },
    {
        "name": "recent_deploys",
        "description": (
            "최근 배포 기록: ASG instance refresh(시작·종료·상태·진행률), ASG 스케일링 활동(인스턴스 생성·종료와 사유), "
            "main 브랜치 최근 커밋. 알람이 배포 직후인지, 무엇이 바뀌었는지 볼 때 쓴다."
        ),
        "input_schema": {"type": "object", "properties": {}, "required": [], "additionalProperties": False},
        "strict": True,
    },
]

# 모델에 넘기기 전 도구 결과의 최대 길이(문자). 넘치면 자르고 표시를 남긴다
MAX_RESULT_CHARS = 7000


def clamp(value, default, lo, hi):
    try:
        value = int(value)
    except (TypeError, ValueError):
        return default
    return max(lo, min(hi, value)) if value > 0 else default


class AwsTools:
    """실제 AWS 를 부르는 구현. 환경변수로 대상 리소스를 받는다(설치 스크립트가 채운다)."""

    def __init__(self, alarm_time, env=None, region=None):
        import boto3  # Lambda 런타임 내장. 로컬 단위 테스트는 FixtureTools 를 쓰므로 지연 import

        env = env or os.environ
        self.region = region or env.get("AWS_REGION", "ap-northeast-2")
        self.alarm_time = alarm_time
        self.log_group = env.get("LOG_GROUP", "")
        self.bucket = env.get("ALB_LOG_BUCKET", "")
        self.prefix = env.get("ALB_LOG_PREFIX", "alb").strip("/")
        self.dims = {
            "LoadBalancer": env.get("ALB_DIM", ""),
            "TargetGroup": env.get("TG_DIM", ""),
            "AutoScalingGroupName": env.get("ASG_NAME", ""),
            "CacheClusterId": env.get("CACHE_ID", ""),
            "DBInstanceIdentifier": env.get("RDS_ID", ""),
        }
        self.github_repo = env.get("GITHUB_REPO", "")
        self.github_token = env.get("GITHUB_TOKEN", "")
        self._logs = boto3.client("logs", region_name=self.region)
        self._cw = boto3.client("cloudwatch", region_name=self.region)
        self._s3 = boto3.client("s3", region_name=self.region)
        self._asg = boto3.client("autoscaling", region_name=self.region)
        self._sts = boto3.client("sts", region_name=self.region)
        self._account_id = None

    # ── 창 계산 ──────────────────────────────────────────────
    def window(self, before_min, after_min=WINDOW_AFTER_MIN):
        start = self.alarm_time - timedelta(minutes=before_min)
        end = min(self.alarm_time + timedelta(minutes=after_min), datetime.now(timezone.utc))
        return start, end

    # ── query_logs ───────────────────────────────────────────
    def query_logs(self, kind="summary", pattern="", window_minutes=WINDOW_BEFORE_MIN):
        if not self.log_group:
            return {"error": "LOG_GROUP 환경변수가 없어 앱 로그를 조회할 수 없다"}
        if kind not in LOG_QUERIES:
            return {"error": f"kind 는 {sorted(LOG_QUERIES)} 중 하나"}
        before = clamp(window_minutes, WINDOW_BEFORE_MIN, 1, MAX_WINDOW_MIN)
        start, end = self.window(before)
        query = LOG_QUERIES[kind]
        if kind == "search":
            safe = re.sub(r"[^\w\s.\-:/\[\]|()*+?]", "", pattern or "")[:80]
            if not safe:
                return {"error": "kind=search 에는 pattern 이 필요하다"}
            query = query.format(pattern=safe)
        started = self._logs.start_query(
            logGroupName=self.log_group,
            startTime=int(start.timestamp()),
            endTime=int(end.timestamp()),
            queryString=query,
            limit=100,
        )
        rows = self._wait_query(started["queryId"])
        if rows is None:
            return {"error": "Logs Insights 질의가 25초 안에 끝나지 않았다", "kind": kind}
        return {
            "kind": kind,
            "window": _fmt_window(start, end),
            "log_group": self.log_group,
            "rows": [_shrink_log_row(r) for r in rows],
        }

    def _wait_query(self, query_id, timeout_s=25):
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            result = self._logs.get_query_results(queryId=query_id)
            if result["status"] in ("Complete", "Failed", "Cancelled", "Timeout"):
                if result["status"] != "Complete":
                    return []
                return [{f["field"]: f["value"] for f in row} for row in result.get("results", [])]
            time.sleep(1)
        try:
            self._logs.stop_query(queryId=query_id)
        except Exception:  # noqa: BLE001 — 정리 실패는 결과에 영향 없음
            pass
        return None

    # ── query_metrics ────────────────────────────────────────
    def query_metrics(self, metric, window_minutes=30):
        spec = METRIC_SPECS.get(metric)
        if not spec:
            return {"error": f"metric 은 {sorted(METRIC_SPECS)} 중 하나"}
        namespace, name, stat, dim_keys = spec
        dims = [{"Name": k, "Value": self.dims[k]} for k in dim_keys]
        if any(not d["Value"] for d in dims):
            return {"error": f"{metric} 에 필요한 차원 환경변수가 비어 있다: {dim_keys}"}
        before = clamp(window_minutes, 30, 1, MAX_WINDOW_MIN)
        start, end = self.window(before, after_min=WINDOW_AFTER_MIN)
        period = 60 if before <= 180 else 300 if before <= 720 else 900
        resp = self._cw.get_metric_data(
            MetricDataQueries=[{
                "Id": "m",
                "MetricStat": {
                    "Metric": {"Namespace": namespace, "MetricName": name, "Dimensions": dims},
                    "Period": period,
                    "Stat": stat,
                },
            }],
            StartTime=start,
            EndTime=end,
            ScanBy="TimestampAscending",
        )
        series = resp["MetricDataResults"][0]
        points = [[t.astimezone(timezone.utc).strftime("%H:%M"), round(v, 3)]
                  for t, v in zip(series["Timestamps"], series["Values"])]
        return {
            "metric": metric,
            "stat": stat,
            "period_s": period,
            "window": _fmt_window(start, end),
            "points": points[-120:],
            "summary": _series_summary([p[1] for p in points]),
        }

    # ── query_access_logs ────────────────────────────────────
    def query_access_logs(self, status_min=0, window_minutes=WINDOW_BEFORE_MIN):
        if not self.bucket:
            return {"error": "ALB_LOG_BUCKET 환경변수가 없어 액세스 로그를 읽을 수 없다"}
        before = clamp(window_minutes, WINDOW_BEFORE_MIN, 1, 60)
        start, end = self.window(before)
        keys = self._access_log_keys(start, end)
        lines = []
        for key in keys[:40]:  # 5분 단위 파일 × 인스턴스 수. 40개면 1시간 이상 커버
            obj = self._s3.get_object(Bucket=self.bucket, Key=key)
            lines.extend(gzip.decompress(obj["Body"].read()).decode("utf-8", "replace").splitlines())
        entries = [e for e in (parse_alb_line(l) for l in lines) if e and start <= e["time"] <= end]
        return summarize_access_entries(entries, status_min, _fmt_window(start, end), files=len(keys))

    def _access_log_keys(self, start, end):
        """ALB 는 5분마다 파일을 쓰고 파일명에 종료 시각이 박힌다. 창을 덮는 파일만 고른다."""
        account = self._account()
        keys = []
        day = start.date()
        while day <= (end + timedelta(minutes=10)).date():
            prefix = f"{self.prefix}/AWSLogs/{account}/elasticloadbalancing/{self.region}/{day:%Y/%m/%d}/"
            token = None
            while True:
                kwargs = {"Bucket": self.bucket, "Prefix": prefix}
                if token:
                    kwargs["ContinuationToken"] = token
                page = self._s3.list_objects_v2(**kwargs)
                for item in page.get("Contents", []):
                    stamp = _key_timestamp(item["Key"])
                    if stamp and start <= stamp <= end + timedelta(minutes=10):
                        keys.append(item["Key"])
                token = page.get("NextContinuationToken")
                if not token:
                    break
            day += timedelta(days=1)
        return sorted(keys)

    def _account(self):
        if not self._account_id:
            self._account_id = self._sts.get_caller_identity()["Account"]
        return self._account_id

    # ── recent_deploys ───────────────────────────────────────
    def recent_deploys(self):
        asg = self.dims["AutoScalingGroupName"]
        out = {"asg": asg, "alarm_time": self.alarm_time.isoformat(), "refreshes": [], "activities": [], "commits": []}
        if asg:
            refreshes = self._asg.describe_instance_refreshes(AutoScalingGroupName=asg, MaxRecords=5)
            for r in refreshes.get("InstanceRefreshes", []):
                out["refreshes"].append({
                    "status": r.get("Status"),
                    "start": _iso(r.get("StartTime")),
                    "end": _iso(r.get("EndTime")),
                    "percentage_complete": r.get("PercentageComplete"),
                    "minutes_before_alarm": _minutes_before(self.alarm_time, r.get("StartTime")),
                })
            activities = self._asg.describe_scaling_activities(AutoScalingGroupName=asg, MaxRecords=10)
            for a in activities.get("Activities", []):
                out["activities"].append({
                    "start": _iso(a.get("StartTime")),
                    "status": a.get("StatusCode"),
                    "description": (a.get("Description") or "")[:160],
                    "cause": (a.get("Cause") or "")[:240],
                })
        if self.github_repo:
            out["commits"] = self._recent_commits()
        return out

    def _recent_commits(self):
        until = self.alarm_time.strftime("%Y-%m-%dT%H:%M:%SZ")
        url = f"https://api.github.com/repos/{self.github_repo}/commits?sha=main&until={until}&per_page=5"
        req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json", "User-Agent": "peakpic-alert-analyze"})
        if self.github_token:
            req.add_header("Authorization", f"Bearer {self.github_token}")
        try:
            with urllib.request.urlopen(req, timeout=8) as resp:
                data = json.loads(resp.read().decode())
        except Exception as e:  # noqa: BLE001 — 커밋은 보조 정보. 실패해도 조사는 계속
            return [{"error": f"GitHub 조회 실패: {type(e).__name__}"}]
        return [{
            "sha": c["sha"][:7],
            "date": (c.get("commit", {}).get("committer") or {}).get("date"),
            "message": (c.get("commit", {}).get("message") or "").splitlines()[0][:120],
        } for c in data]


# ── 공용 파서·요약 (AWS 무관, 단위 테스트 대상) ─────────────────

# ALB 액세스 로그 필드 순서(공식 문서). 따옴표 묶음은 shlex 로 푼다
_ALB_FIELDS = [
    "type", "time", "elb", "client", "target", "request_processing_time", "target_processing_time",
    "response_processing_time", "elb_status_code", "target_status_code", "received_bytes", "sent_bytes",
    "request", "user_agent", "ssl_cipher", "ssl_protocol", "target_group_arn", "trace_id", "domain_name",
    "chosen_cert_arn", "matched_rule_priority", "request_creation_time", "actions_executed", "redirect_url",
    "error_reason", "target_port_list", "target_status_code_list", "classification", "classification_reason",
]


def parse_alb_line(line):
    """ALB 액세스 로그 한 줄 → dict. 깨진 줄은 None."""
    try:
        parts = shlex.split(line, posix=True)
    except ValueError:
        return None
    if len(parts) < 14:
        return None
    row = dict(zip(_ALB_FIELDS, parts))
    try:
        when = datetime.fromisoformat(row["time"].replace("Z", "+00:00"))
    except ValueError:
        return None
    request = row["request"].split(" ")
    method = request[0] if request else "-"
    path = "-"
    if len(request) >= 2:
        path = re.sub(r"^https?://[^/]+", "", request[1]).split("?")[0] or "/"
    return {
        "time": when,
        "client_ip": row["client"].rsplit(":", 1)[0],
        "status": _to_int(row["elb_status_code"]),
        "target_status": row["target_status_code"],
        "method": method,
        "path": path,
        "user_agent": row["user_agent"][:120],
    }


def summarize_access_entries(entries, status_min=0, window="", files=0):
    """파싱된 요청 목록을 집계한다. 개별 요청은 돌려주지 않는다(크기·개인정보)."""
    total = len(entries)
    picked = [e for e in entries if status_min <= 0 or (e["status"] or 0) >= status_min]
    by_status = Counter(e["status"] for e in picked)
    top_ips = Counter(e["client_ip"] for e in picked).most_common(10)
    top_ua = Counter(e["user_agent"] for e in picked).most_common(5)
    top_req = Counter(f'{e["method"]} {e["path"]}' for e in picked).most_common(12)
    return {
        "window": window,
        "files_read": files,
        "requests_in_window": total,
        "matched": len(picked),
        "status_min": status_min,
        "by_status": {str(k): v for k, v in sorted(by_status.items(), key=lambda kv: -kv[1])},
        "top_client_ips": [{"ip": ip, "n": n} for ip, n in top_ips],
        "top_user_agents": [{"ua": ua, "n": n} for ua, n in top_ua],
        "top_requests": [{"request": r, "n": n} for r, n in top_req],
        "distinct_ips": len({e["client_ip"] for e in picked}),
    }


def _key_timestamp(key):
    m = re.search(r"_(\d{8}T\d{4})Z_", key)
    if not m:
        return None
    return datetime.strptime(m.group(1), "%Y%m%dT%H%M").replace(tzinfo=timezone.utc)


def _shrink_log_row(row):
    """Insights 행에서 긴 필드를 자른다. 스택은 예외 클래스가 보이는 첫 3줄이면 충분하다."""
    out = {}
    for k, v in row.items():
        if k.startswith("@ptr"):
            continue
        if k == "stack_trace" and v:
            v = "\n".join(v.splitlines()[:3])
        if isinstance(v, str) and len(v) > 300:
            v = v[:300] + "…"
        out[k] = v
    return out


def _series_summary(values):
    if not values:
        return {"count": 0}
    return {
        "count": len(values),
        "min": round(min(values), 3),
        "max": round(max(values), 3),
        "sum": round(sum(values), 3),
        "last": round(values[-1], 3),
    }


def _fmt_window(start, end):
    return f"{start.strftime('%Y-%m-%d %H:%M')}Z ~ {end.strftime('%H:%M')}Z"


def _iso(dt):
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ") if dt else None


def _minutes_before(alarm_time, dt):
    if not dt:
        return None
    return round((alarm_time - dt.astimezone(timezone.utc)).total_seconds() / 60)


def _to_int(text):
    try:
        return int(text)
    except (TypeError, ValueError):
        return None


def truncate_result(payload):
    """도구 결과를 JSON 문자열로 만들고 상한을 넘기면 자른다."""
    text = json.dumps(payload, ensure_ascii=False, default=str)
    if len(text) > MAX_RESULT_CHARS:
        text = text[:MAX_RESULT_CHARS] + f'… (잘림, 전체 {len(text)}자)'
    return text
