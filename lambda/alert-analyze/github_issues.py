"""GitHub 이슈 쓰기(#233): 지문으로 찾기 → 새로 만들기 또는 재발 코멘트. urllib 만 쓴다.

모드(ISSUE_CREATE):
  off     아무것도 하지 않는다
  shadow  찾기만 하고(공개 레포는 토큰 없이도 된다) 만들지 않는다 — '만들었을 이슈'를 Discord 에 보여 정밀도를 잰다
  on      실제로 만든다. fine-grained PAT(이 레포 한정, Issues 쓰기) 필요
"""

import json
import urllib.error
import urllib.parse
import urllib.request

from triage import FALSE_POSITIVE_LABEL, comment_body, issue_body, marker

API = "https://api.github.com"
USER_AGENT = "peakpic-alert-analyze/1.0"
TIMEOUT = 10


class GitHubIssues:
    def __init__(self, repo, token=""):
        self.repo = repo
        self.token = token or ""

    def find_by_fingerprint(self, fp):
        """본문에 지문 표식이 있는 이슈(열림·닫힘 모두). 검색 색인이 몇 초~몇 분 늦을 수 있다."""
        q = f'repo:{self.repo} is:issue "{marker(fp)}" in:body'
        data = self._call("GET", f"/search/issues?q={urllib.parse.quote(q)}&per_page=10")
        items = []
        for item in data.get("items", []):
            items.append({"number": item["number"], "state": item.get("state"), "html_url": item.get("html_url"),
                          "labels": [label.get("name") for label in item.get("labels", [])]})
        return items

    def create(self, title, body, labels):
        data = self._call("POST", f"/repos/{self.repo}/issues", {"title": title, "body": body, "labels": labels})
        return {"number": data["number"], "html_url": data["html_url"]}

    def comment(self, number, body):
        data = self._call("POST", f"/repos/{self.repo}/issues/{number}/comments", {"body": body})
        return {"html_url": data.get("html_url")}

    def count_marker_comments(self, number, fp):
        """같은 지문 표식이 든 코멘트 수. N회째 계산용(본문이 1회째)."""
        data = self._call("GET", f"/repos/{self.repo}/issues/{number}/comments?per_page=100")
        return sum(1 for c in data if marker(fp) in (c.get("body") or ""))

    def _call(self, method, path, payload=None):
        headers = {"Accept": "application/vnd.github+json", "User-Agent": USER_AGENT, "X-GitHub-Api-Version": "2022-11-28"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        body = json.dumps(payload).encode("utf-8") if payload is not None else None
        if body is not None:
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(API + path, data=body, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
                return json.loads(resp.read().decode("utf-8") or "{}")
        except urllib.error.HTTPError as e:
            raise RuntimeError(f"GitHub {method} {path.split('?')[0]} → {e.code}") from None


def sync(gh, draft, mode, secrets=()):
    """초안을 모드에 따라 처리한다. 결과 {"action": created|commented|skipped|shadow, "number", "url", "nth", "note"}.
    GitHub 호출 실패는 RuntimeError 로 올라간다 — 호출자가 잡아 Discord 에 '실패'로 적는다."""
    fp = draft["fingerprint"]
    existing = gh.find_by_fingerprint(fp)
    flagged = [i for i in existing if FALSE_POSITIVE_LABEL in i["labels"]]
    if flagged:
        return {"action": "skipped", "number": flagged[0]["number"], "url": flagged[0]["html_url"], "nth": 0,
                "note": "오탐으로 표시된 지문"}
    open_issues = [i for i in existing if i["state"] == "open"]
    if mode == "shadow":
        if open_issues:
            return {"action": "shadow", "number": open_issues[0]["number"], "url": open_issues[0]["html_url"], "nth": 0,
                    "note": f"#{open_issues[0]['number']} 에 코멘트했을 것"}
        return {"action": "shadow", "number": 0, "url": "", "nth": 0, "note": "새 이슈를 만들었을 것"}
    if open_issues:
        target = open_issues[0]
        nth = gh.count_marker_comments(target["number"], fp) + 2
        comment = gh.comment(target["number"], comment_body(nth, draft, secrets))
        return {"action": "commented", "number": target["number"], "url": comment.get("html_url") or target["html_url"],
                "nth": nth, "note": ""}
    created = gh.create(draft["title"], issue_body(draft, secrets), draft["labels"])
    return {"action": "created", "number": created["number"], "url": created["html_url"], "nth": 1, "note": ""}
