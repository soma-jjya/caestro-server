"""알람 이름 → 사람 말 제목·서비스. alert-notify 와 alert-analyze 가 같은 파일을 쓴다 (#249, #254).

setup-pipeline.sh 는 이 파일을 alert-notify zip 에, setup-analyze.sh 는 alert-analyze 패키지에 함께 넣는다.
새 알람을 만들면 여기 접미사를 추가한다.
"""

B2B_PREFIX = "peakpic-b2b-"
B2C_SERVICE = "앱 (B2C)"

# 알람 이름 접미사 → (제목, 무슨 일인지, 손님 영향). B2C create-alarms.sh 와 B2B observability.tf 가 같은 접미사를 쓴다.
ALARM_CATALOG = {
    "alb-unhealthy-host": (
        "서버 응답 없음",
        "헬스체크에 실패한 서버가 5분째 있어요.",
        "그 서버로 간 요청이 실패하거나 느려질 수 있어요. 배포 중이면 곧 해제돼요.",
    ),
    "ec2-status-check-failed": (
        "서버 상태 검사 실패",
        "서버(EC2) 자체 상태 검사에 3분째 실패하고 있어요.",
        "서버 한 대가 사실상 죽은 상태라 용량이 줄어요.",
    ),
    "redis-connections-lost": (
        "Redis 연결 끊김",
        "앱과 Redis 사이 연결이 0이에요.",
        "세션·대기열·한도 등 Redis 를 쓰는 기능이 전부 멈춰요.",
    ),
    "rds-low-storage": (
        "DB 저장 공간 부족",
        "DB 여유 공간이 4GiB 아래로 내려갔어요.",
        "지금 당장은 괜찮지만 계속 줄면 저장이 실패해요. 자동 확장이 되는지 확인하세요.",
    ),
    "ec2-cpu-credit-low": (
        "CPU 크레딧 낮음",
        "버스트 크레딧 잔고가 15 아래예요.",
        "단독으로는 알리지 않는 재료 알람이에요.",
    ),
    "ec2-cpu-busy": (
        "CPU 바쁨",
        "CPU 평균이 15분째 15% 를 넘어요.",
        "단독으로는 알리지 않는 재료 알람이에요.",
    ),
    "ec2-cpu-credit-exhausting": (
        "CPU 크레딧 소진 임박",
        "크레딧이 15 아래인데 CPU 를 계속 쓰고 있어요.",
        "0 이 되면 서버가 느려지거나(standard) 추가 과금(unlimited)이 나요.",
    ),
    "alb-5xx-spike": (
        "서버 오류(5xx) 급증",
        "서버가 5분 동안 5xx 오류를 5건 넘게 냈어요.",
        "일부 손님에게 오류 화면이 보였을 수 있어요. 잠시 뒤 분석 알림이 따라와요.",
    ),
    "alb-elb-5xx": (
        "서버 미응답 (로드밸런서 5xx)",
        "로드밸런서가 서버 응답을 못 받아 502·503·504 를 1분에 10건 넘게 냈어요.",
        "그 시간에 접속한 손님은 오류를 봤어요. 서버가 멈췄거나 교체 중일 수 있어요.",
    ),
    "host-memory-high": (
        "서버 메모리 부족",
        "서버 메모리 사용이 3분째 80% 를 넘어요.",
        "이대로면 서버가 멈출 수 있어요.",
    ),
    "redis-memory-high": (
        "Redis 메모리 높음",
        "Redis 메모리 사용률이 75% 를 넘었어요.",
        "한계에 닿으면 세션이 밀려나 유실돼요.",
    ),
}



def classify_alarm(name):
    """알람 이름 → (서비스 라벨, 카탈로그 접미사). 모르는 이름은 ('', '')."""
    for suffix in sorted(ALARM_CATALOG, key=len, reverse=True):
        if not name.endswith("-" + suffix):
            continue
        head = name[: -len(suffix) - 1]
        if head.startswith(B2B_PREFIX):
            return f"축제 · {head[len(B2B_PREFIX):]}", suffix
        if head == "peakpic":
            return B2C_SERVICE, suffix
        return head, suffix
    return "", ""



def alarm_headline(name):
    """'서버 오류(5xx) 급증 · 앱 (B2C)' 처럼 제목에 쓰는 한 줄. 모르는 알람은 이름 그대로."""
    service, suffix = classify_alarm(name)
    title = ALARM_CATALOG[suffix][0] if suffix else name
    return f"{title} · {service}" if service else title
