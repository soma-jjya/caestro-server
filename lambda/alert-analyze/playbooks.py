"""알람별 플레이북: 모델을 부르기 전에 미리 돌려 둘 도구 호출과, 그 알람에서 사람이 먼저 묻는 질문.

사람이 처음 던지는 질문은 알람마다 다르다. 5xx 는 "누가 어떤 경로를 불러 무슨 예외가 났나",
언헬시 호스트는 "배포 중인가, 인스턴스가 죽었나, 앱이 멈췄나". 미리 가져오면 모델 왕복을 1~2회 아낀다.
"""

# 알람 이름 접미(peakpic- 접두 제외) → {"prefetch": [(도구, 인자)], "hint": 조사 안내, "depth": full|summary}
PLAYBOOKS = {
    "alb-5xx-spike": {
        "depth": "full",
        "prefetch": [
            ("query_logs", {"kind": "errors", "pattern": "", "window_minutes": 5}),
            ("query_access_logs", {"status_min": 500, "window_minutes": 5}),
            ("recent_deploys", {}),
        ],
        "hint": (
            "먼저 가릴 것: 외부 스캐너(없는 경로·이상한 UA·소수 IP 집중)인가, 앱 결함(예외 스택이 우리 코드)인가, "
            "직전 배포 때문인가. 사용자 영향은 정상 경로(/auth, /sessions, /signaling, /fill 등)의 5xx 가 있는지로 판단한다. "
            "없는 경로를 불러 생긴 5xx 는 서버가 404 를 내야 하는 결함이기도 하다(#137 에서 수정됨 — 다시 나면 회귀)."
        ),
    },
    "alb-unhealthy-host": {
        "depth": "full",
        "prefetch": [
            ("recent_deploys", {}),
            ("query_metrics", {"metric": "alb_healthy_hosts", "window_minutes": 30}),
            ("query_logs", {"kind": "errors", "pattern": "", "window_minutes": 10}),
        ],
        "hint": (
            "먼저 가릴 것: 배포(instance refresh 진행 중)로 인스턴스가 교체되는 정상 구간인가, 새 인스턴스가 기동에 실패했나"
            "(Spring 컨텍스트 예외·포트 바인딩·DB/Redis 연결 실패), 멀쩡하던 인스턴스가 죽었나. "
            "healthy 가 0 이면 전면 장애, 1 이상이면 용량 절반 손실이다."
        ),
    },
    "ec2-status-check-failed": {
        "depth": "full",
        "prefetch": [
            ("recent_deploys", {}),
            ("query_metrics", {"metric": "ec2_cpu", "window_minutes": 30}),
            ("query_logs", {"kind": "volume", "pattern": "", "window_minutes": 15}),
        ],
        "hint": (
            "상태 검사 실패는 호스트(EC2 하드웨어·네트워크) 또는 인스턴스(OS·메모리 고갈) 수준 문제다. "
            "로그가 알람 전에 끊겼는지(volume 0), 직전에 OOM·GC 폭주 흔적이 있는지, 배포로 막 뜬 인스턴스인지 본다."
        ),
    },
    "redis-connections-lost": {
        "depth": "summary",
        "prefetch": [
            ("query_metrics", {"metric": "redis_connections", "window_minutes": 60}),
            ("query_logs", {"kind": "search", "pattern": "Redis|Lettuce|RedisConnection", "window_minutes": 10}),
            ("recent_deploys", {}),
        ],
        "hint": "앱이 Redis 에 못 붙는지(앱 로그에 연결 예외) Redis 자체가 재시작됐는지(지표 공백) 가린다. 시그널링 전면 마비 여부가 핵심.",
    },
    "redis-memory-high": {
        "depth": "summary",
        "prefetch": [
            ("query_metrics", {"metric": "redis_memory_pct", "window_minutes": 1440}),
            ("query_metrics", {"metric": "redis_evictions", "window_minutes": 1440}),
        ],
        "hint": "24시간 추세가 완만한 증가인지 급증인지, evictions 가 시작됐는지만 요약한다. 키 분포는 여기서 볼 수 없다.",
    },
    "ec2-cpu-credit-low": {
        "depth": "summary",
        "prefetch": [
            ("recent_deploys", {}),
            ("query_metrics", {"metric": "ec2_cpu_credit", "window_minutes": 180}),
            ("query_metrics", {"metric": "ec2_cpu", "window_minutes": 180}),
        ],
        "hint": (
            "t3 는 새 인스턴스가 잔고 0 에서 시작해 5분에 1 크레딧씩 쌓인다. 배포 직후면 오탐(false_positive). "
            "배포가 없는데 CPU 가 기준선(10%) 위로 계속이면 진짜 소진이다."
        ),
    },
    "rds-low-storage": {
        "depth": "summary",
        "prefetch": [
            ("query_metrics", {"metric": "rds_free_storage_bytes", "window_minutes": 1440}),
        ],
        "hint": "24시간 기울기로 며칠 뒤 바닥나는지 추정한다. 스토리지 오토스케일링이 작동했는지(값이 계단식으로 늘었는지) 본다.",
    },
}

DEFAULT_PLAYBOOK = {
    "depth": "summary",
    "prefetch": [("recent_deploys", {}), ("query_logs", {"kind": "summary", "pattern": "", "window_minutes": 10})],
    "hint": "등록되지 않은 알람이다. 배포 여부와 에러 로그 요약만 보고 보수적으로 결론 낸다.",
}


def playbook_for(alarm_name):
    """알람 이름(접두 peakpic- 유무 무관)에 맞는 플레이북. 없으면 기본."""
    key = (alarm_name or "").strip()
    if key.startswith("peakpic-"):
        key = key[len("peakpic-"):]
    return PLAYBOOKS.get(key, DEFAULT_PLAYBOOK)
