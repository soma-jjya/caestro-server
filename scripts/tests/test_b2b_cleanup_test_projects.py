import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("cleanup", Path(__file__).parents[1] / "b2b_cleanup_test_projects.py")
cleanup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cleanup)


class FakeApi:
    def __init__(self):
        self.projects = {key: {"id": key, "slug": slug, "status": "PAUSED", "frames": []}
                         for key, slug in cleanup.TARGETS.items()}
        protected, slug = cleanup.PROTECTED
        self.projects[protected] = {"id": protected, "slug": slug, "status": "LIVE", "frames": [{"id": "real-frame"}]}
        self.projects["bp_other"] = {"id": "bp_other", "slug": "other-store", "status": "DRAFT", "frames": []}
        self.deleted = []
        self.deployed = True
        self.fail_on = None
        self.change_yuja = False

    def list_projects(self):
        return copy.deepcopy(list(self.projects.values()))

    def get_project(self, project_id):
        return copy.deepcopy(self.projects.get(project_id))

    def supports_delete(self):
        return self.deployed

    def delete_project(self, project_id):
        if project_id == self.fail_on:
            raise cleanup.StopCleanup("simulated failure")
        self.deleted.append(project_id)
        del self.projects[project_id]
        if self.change_yuja:
            self.projects[cleanup.PROTECTED[0]]["name"] = "concurrent edit"


class CleanupTest(unittest.TestCase):
    def test_default_only_previews(self):
        api = FakeApi()
        report = cleanup.cleanup(api)
        self.assertEqual(len(report["selected"]), 11)
        self.assertFalse(report["verified"])
        self.assertEqual(api.deleted, [])

    def test_execute_deletes_exact_allowlist_and_preserves_all_others(self):
        api = FakeApi()
        before = api.get_project(cleanup.PROTECTED[0])
        report = cleanup.cleanup(api, execute=True)
        self.assertEqual(api.deleted, list(cleanup.TARGETS))
        self.assertEqual(set(api.projects), {cleanup.PROTECTED[0], "bp_other"})
        self.assertEqual(api.get_project(cleanup.PROTECTED[0]), before)
        self.assertTrue(report["verified"])

    def test_last_target_live_blocks_every_delete(self):
        api = FakeApi()
        api.projects[list(cleanup.TARGETS)[-1]]["status"] = "LIVE"
        with self.assertRaises(cleanup.StopCleanup):
            cleanup.cleanup(api, execute=True)
        self.assertEqual(api.deleted, [])

    def test_slug_mismatch_blocks_every_delete(self):
        api = FakeApi()
        api.projects[next(iter(cleanup.TARGETS))]["slug"] = "real-festival"
        with self.assertRaises(cleanup.StopCleanup):
            cleanup.cleanup(api, execute=True)
        self.assertEqual(api.deleted, [])

    def test_missing_yuja_blocks_every_delete(self):
        api = FakeApi()
        del api.projects[cleanup.PROTECTED[0]]
        with self.assertRaises(cleanup.StopCleanup):
            cleanup.cleanup(api, execute=True)
        self.assertEqual(api.deleted, [])

    def test_missing_endpoint_blocks_every_delete(self):
        api = FakeApi()
        api.deployed = False
        with self.assertRaises(cleanup.StopCleanup):
            cleanup.cleanup(api, execute=True)
        self.assertEqual(api.deleted, [])

    def test_partial_failure_is_reported_and_can_resume_without_redeleting(self):
        api = FakeApi()
        ids = list(cleanup.TARGETS)
        api.fail_on = ids[2]
        report = {}
        with self.assertRaises(cleanup.StopCleanup):
            cleanup.cleanup(api, execute=True, report=report)
        self.assertEqual(report["deleted"], ids[:2])
        self.assertFalse(report["verified"])
        api.fail_on = None
        retried = cleanup.cleanup(api, execute=True)
        self.assertEqual(retried["alreadyAbsent"], ids[:2])
        self.assertEqual(api.deleted, ids)
        self.assertTrue(retried["verified"])

    def test_reused_slug_with_different_id_is_not_deleted(self):
        api = FakeApi()
        project_id = next(iter(cleanup.TARGETS))
        replaced = api.projects.pop(project_id)
        replaced["id"] = "bp_recreated"
        api.projects["bp_recreated"] = replaced
        with self.assertRaises(cleanup.StopCleanup):
            cleanup.cleanup(api, execute=True)
        self.assertEqual(api.deleted, [])

    def test_yuja_change_stops_after_first_delete(self):
        api = FakeApi()
        api.change_yuja = True
        with self.assertRaises(cleanup.StopCleanup):
            cleanup.cleanup(api, execute=True)
        self.assertEqual(len(api.deleted), 1)
        self.assertIn(cleanup.PROTECTED[0], api.projects)


if __name__ == "__main__":
    unittest.main()
