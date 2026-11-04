"""AWS 없이 조사 루프를 돌리기 위한 도구 대역. 골든 셋 재생(eval/replay.py)과 로컬 테스트가 쓴다.

픽스처 형식: {"query_logs": {"errors": {...}, "summary": {...}, "default": {...}},
             "query_metrics": {"alb_5xx": {...}, "default": {...}},
             "query_access_logs": {"500": {...}, "default": {...}},
             "recent_deploys": {...}}
"""


class FixtureTools:
    def __init__(self, data):
        self.data = data or {}
        self.calls = []

    def _pick(self, tool, key):
        table = self.data.get(tool)
        if not isinstance(table, dict):
            return {"error": f"픽스처에 {tool} 이 없다"}
        if key in table:
            return table[key]
        return table.get("default", {"error": f"픽스처 {tool}[{key}] 없음 — 이 창에는 해당 데이터가 없다"})

    def query_logs(self, kind="summary", pattern="", window_minutes=5):
        self.calls.append(("query_logs", kind))
        return self._pick("query_logs", kind)

    def query_metrics(self, metric, window_minutes=30):
        self.calls.append(("query_metrics", metric))
        return self._pick("query_metrics", metric)

    def query_access_logs(self, status_min=0, window_minutes=5):
        self.calls.append(("query_access_logs", str(status_min)))
        return self._pick("query_access_logs", str(status_min))

    def recent_deploys(self):
        self.calls.append(("recent_deploys", ""))
        return self.data.get("recent_deploys", {"error": "픽스처에 recent_deploys 가 없다"})
