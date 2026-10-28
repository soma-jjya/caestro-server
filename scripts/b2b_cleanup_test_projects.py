#!/usr/bin/env python3
"""Explicit allowlist cleanup for the eleven B2B test projects; dry-run by default."""
import argparse
import copy
import getpass
import json
import os
from pathlib import Path
import sys
import tempfile
from datetime import datetime, timezone
from urllib.error import HTTPError
from urllib.request import Request, build_opener, HTTPRedirectHandler

PROTECTED = ("bp_0613bde0c0954e879bc4f3702ad83f88", "yuja")
TARGETS = {
    "bp_f479cd202c7f437ca46b3f51a6d5d516": "live-check-1u5fu",
    "bp_981b96c387b641758a1bc47a686707ee": "quick-q0iex",
    "bp_d4a1757e3eb341d187cbf54323f6d74e": "quick-q3a6n",
    "bp_faf9b970940043be95ab1eda1fd0ae13": "quick-q4fag",
    "bp_9c386367d410476a9f0b5c6d39621adc": "quick-q5twh",
    "bp_9b17130715ee47e8a6fbf60d7ab656b0": "quick-q8ilu",
    "bp_490458b5b233406cb19ba5aed4d3eddd": "quick-06ux9",
    "bp_6e101dc19cc0483e8652a8d5ea542f28": "quick1-q8ilu",
    "bp_b01d6b19ec6748c5a2642eafa498abde": "quick1-q5twh",
    "bp_f55d1190099b4cf1bfe7c21a155f887b": "quick1-q4fag",
    "bp_76eb610e8ed44fb89efe71ea38faafa1": "quick1-06ux9",
}


class StopCleanup(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward the studio key to a redirect target.
        return None


class Api:
    BASE = "https://api.peakpic.app"

    def __init__(self, key):
        self.key = key
        self.opener = build_opener(NoRedirect())

    def request(self, method, path, expected):
        headers = {"Accept": "application/json"}
        if path.startswith("/b2b/admin/"):
            headers["X-Studio-Key"] = self.key
        try:
            with self.opener.open(Request(self.BASE + path, method=method, headers=headers), timeout=60) as response:
                if response.status != expected:
                    raise StopCleanup(f"{method} {path}: expected {expected}, got {response.status}")
                return None if expected == 204 else json.load(response)
        except HTTPError as error:
            if error.code == 404 and method == "GET" and path.startswith("/b2b/admin/projects/"):
                return None
            raise StopCleanup(f"{method} {path}: HTTP {error.code}; no further deletion performed") from None

    def list_projects(self):
        return self.request("GET", "/b2b/admin/projects", 200)

    def get_project(self, project_id):
        return self.request("GET", "/b2b/admin/projects/" + project_id, 200)

    def supports_delete(self):
        spec = self.request("GET", "/v3/api-docs", 200)
        return "delete" in spec.get("paths", {}).get("/b2b/admin/projects/{id}", {})

    def delete_project(self, project_id):
        self.request("DELETE", "/b2b/admin/projects/" + project_id, 204)


def indexed(rows):
    if not isinstance(rows, list) or any(not isinstance(p, dict) or "id" not in p or "slug" not in p for p in rows):
        raise StopCleanup("Unexpected project list response")
    result = {p["id"]: p for p in rows}
    if len(result) != len(rows) or len({p["slug"] for p in rows}) != len(rows):
        raise StopCleanup("Duplicate project IDs or slugs in response")
    if result.get(PROTECTED[0], {}).get("slug") != PROTECTED[1]:
        raise StopCleanup("Protected yuja project is missing or has a different slug")
    return result


def cleanup(api, execute=False, report=None, save=lambda: None):
    report = report if report is not None else {}
    report.update(dryRun=not execute, deleted=[], alreadyAbsent=[], verified=False)
    assert len(TARGETS) == 11 and PROTECTED[0] not in TARGETS and PROTECTED[1] not in TARGETS.values()
    before = indexed(api.list_projects())
    preserved = copy.deepcopy({key: value for key, value in before.items() if key not in TARGETS})
    selected = []
    # Preflight every target before deleting anything; no slug prefix matching or auto-pause.
    for project_id, slug in TARGETS.items():
        current = before.get(project_id)
        if current is None:
            if any(p["slug"] == slug for p in before.values()):
                raise StopCleanup(f"Slug {slug} now belongs to a different ID")
            report["alreadyAbsent"].append(project_id)
            continue
        if current["slug"] != slug or current.get("status") not in ("DRAFT", "PAUSED"):
            raise StopCleanup(f"Target mismatch or LIVE status: {project_id} ({slug})")
        selected.append(copy.deepcopy(current))
    report["selected"] = [{"id": p["id"], "slug": p["slug"], "status": p["status"],
                           "frameCount": len(p.get("frames", []))} for p in selected]
    report["preservedIds"] = list(preserved)
    save()
    if not execute:
        return report
    if not api.supports_delete():
        raise StopCleanup("DELETE endpoint is not present in deployed OpenAPI; deploy the API first")
    for target in selected:
        if api.get_project(PROTECTED[0]) != preserved[PROTECTED[0]]:
            raise StopCleanup("Protected yuja changed during cleanup; stopping")
        if api.get_project(target["id"]) != target:
            raise StopCleanup(f"Target changed after preflight: {target['id']}")
        api.delete_project(target["id"])
        report["deleted"].append(target["id"])
        save()
        if api.get_project(target["id"]) is not None:
            raise StopCleanup(f"Deleted project is still visible: {target['id']}")
    after = indexed(api.list_projects())
    if any(project_id in after for project_id in TARGETS):
        raise StopCleanup("Some target projects remain")
    if any(after.get(project_id) != project for project_id, project in preserved.items()):
        raise StopCleanup("A non-target project changed; review the report")
    report["verified"] = True
    save()
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true", help="Delete the exact eleven allowlisted projects")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    parser.add_argument("--report", default=str(Path(tempfile.gettempdir()) / f"b2b-cleanup-{stamp}.json"))
    args = parser.parse_args()
    report = {"startedAt": stamp}
    report_path = Path(args.report)

    def save():
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    key = os.environ.get("STUDIO_KEY") or getpass.getpass("STUDIO_KEY (hidden): ")
    if not key.strip():
        parser.error("STUDIO_KEY is required")
    try:
        cleanup(Api(key), args.execute, report, save)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        print("Report:", report_path)
        return 0
    except Exception as error:
        # No header, credential, or HTTP response bodies are written to the report.
        report["error"] = str(error) if isinstance(error, StopCleanup) else type(error).__name__
        save()
        print("Stopped:", report["error"], "Report:", report_path, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
