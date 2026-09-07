import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('selectel_ui', Path(__file__).parents[1] / 'selectel_ui.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class FakeClient:
    project = 'ci'
    def __init__(self, slot='owned-slot', fail_session=False):
        self.slot = slot
        self.fail_session = fail_session
        self.calls = []
    def request(self, method, route, body=None):
        self.calls.append((method, route, body))
        if method == 'GET':
            return {'meta': {'slot': {'id': self.slot}}}
        if self.fail_session and '/v1/' in route:
            raise m.ApiError(method, route, 500)


class LeaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'lease.json'
        self.state = {'project': 'ci', 'devices': [{'serial': 'test-device', 'slot': 'owned-slot'}],
                      'baseline': ['existing-device'], 'released': False}
        m.write_state(self.path, self.state)
        self.adb = patch.object(m, 'adb', return_value='').start()
        self.addCleanup(patch.stopall)

    def test_release_ends_rental_even_when_adb_session_fails(self):
        client = FakeClient(fail_session=True)
        m.release(client, self.path)
        self.assertIn(('DELETE', '/v3/devices/test-device', {'slotID': 'owned-slot'}), client.calls)
        self.assertTrue(m.read_state(self.path)['released'])

    def test_stale_journal_cannot_delete_reallocated_device(self):
        client = FakeClient(slot='someone-elses-slot')
        m.release(client, self.path)
        self.assertFalse(any(call[0] == 'DELETE' for call in client.calls))

    def test_preexisting_device_cannot_be_deleted(self):
        self.state['baseline'].append('test-device')
        m.write_state(self.path, self.state)
        client = FakeClient()
        with self.assertRaisesRegex(RuntimeError, 'pre-existing'):
            m.release(client, self.path)
        self.assertEqual([], client.calls)

    def test_release_is_idempotent(self):
        client = FakeClient()
        m.release(client, self.path)
        count = len(client.calls)
        m.release(client, self.path)
        self.assertEqual(count, len(client.calls))

    def test_lost_allocation_response_requires_recovery(self):
        self.state.update(devices=[], allocation_pending=True)
        m.write_state(self.path, self.state)
        with self.assertRaisesRegex(RuntimeError, 'response was lost'):
            m.release(FakeClient(), self.path)
        self.assertFalse(m.read_state(self.path)['released'])

    def test_wrong_project_is_rejected(self):
        client = FakeClient()
        client.project = 'other-project'
        with self.assertRaisesRegex(RuntimeError, 'different project'):
            m.release(client, self.path)
        self.assertEqual([], client.calls)

    def test_zero_tests_or_crashed_instrumentation_is_failure(self):
        for output in ['', 'INSTRUMENTATION_CODE: -1', 'OK (0 tests)\nINSTRUMENTATION_CODE: -1']:
            self.assertFalse(m.instrumentation_report(output, Path(self.temp.name) / 'junit.xml'))

    def test_android_failure_is_not_hidden_by_successful_adb_exit(self):
        output = ('INSTRUMENTATION_STATUS: class=Test\nINSTRUMENTATION_STATUS: test=broken\n'
                  'INSTRUMENTATION_STATUS: stack=Assertion failed\nINSTRUMENTATION_STATUS_CODE: -2\n'
                  'INSTRUMENTATION_CODE: -1')
        self.assertFalse(m.instrumentation_report(output, Path(self.temp.name) / 'junit.xml'))

    def test_all_skipped_is_not_a_successful_smoke_run(self):
        output = ('INSTRUMENTATION_STATUS: class=Test\nINSTRUMENTATION_STATUS: test=skipped\n'
                  'INSTRUMENTATION_STATUS_CODE: -3\nOK (0 tests)\nINSTRUMENTATION_CODE: -1')
        self.assertFalse(m.instrumentation_report(output, Path(self.temp.name) / 'junit.xml'))

    def test_successful_instrumentation(self):
        output = ('INSTRUMENTATION_STATUS: class=Test\nINSTRUMENTATION_STATUS: test=works\n'
                  'INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1')
        self.assertTrue(m.instrumentation_report(output, Path(self.temp.name) / 'junit.xml'))


if __name__ == '__main__':
    unittest.main()
