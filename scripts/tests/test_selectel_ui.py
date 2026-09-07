import importlib.util
import json
import os
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


class AdbConnectionTest(unittest.TestCase):
    def test_offline_transport_is_reconnected_without_renting_again(self):
        with patch.object(m, 'adb', side_effect=['connected', 'error: device offline', '',
                                                'connected', 'device']) as adb, \
                patch.object(m.time, 'sleep'), patch.object(m, 'Client', side_effect=AssertionError):
            m.connect_device(Path('/tmp/test-lease.json'), 'example.test:1234')
        commands = [call.args[1:] for call in adb.call_args_list]
        self.assertEqual([('connect', 'example.test:1234'),
                          ('-s', 'example.test:1234', 'get-state'),
                          ('disconnect', 'example.test:1234'),
                          ('connect', 'example.test:1234'),
                          ('-s', 'example.test:1234', 'get-state')], commands)


class MatrixTest(unittest.TestCase):
    def test_fixed_catalog_needs_no_credentials_or_api(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(m, 'Client', side_effect=AssertionError):
            devices = m.device_matrix('all')
            single = m.device_matrix('single')
        self.assertEqual(1, len(single))
        self.assertEqual('Galaxy A14', single[0]['model'])
        self.assertIn(single[0], devices)
        self.assertEqual(len(devices), len({d['id'] for d in devices}))
        self.assertEqual({'arm64-v8a'}, {d['abi'] for d in devices})
        self.assertEqual(['30', '33', '35', '36', '37'], sorted(d['api'] for d in devices))

    def test_selection_does_not_substitute_another_configuration(self):
        device = m.device_matrix('single')[0]
        with patch.dict(os.environ):
            m.select_configuration(device)
            candidate = dict(platform='Android', sdk='35', marketName='Galaxy A14',
                             manufacturer='SAMSUNG', abi='arm64-v8a', count=1)
            self.assertEqual(candidate, m.choose_device([candidate]))
            for field, value in [('sdk', '33'), ('abi', 'armeabi-v7a'),
                                 ('manufacturer', 'OTHER'), ('count', 0)]:
                with self.assertRaisesRegex(RuntimeError, 'Unavailable configuration'):
                    m.choose_device([dict(candidate, **{field: value})])

    def test_cleanup_attempts_every_journal_after_failure(self):
        with tempfile.TemporaryDirectory() as root:
            paths = [Path(root) / name / 'lease.json' for name in ['a', 'b']]
            for path in paths:
                m.write_state(path, {})
            with patch.object(m, 'release', side_effect=[RuntimeError('offline'), None]) as release:
                with self.assertRaisesRegex(RuntimeError, 'offline'):
                    m.release_all(None, Path(root))
            self.assertEqual(paths, [call.args[1] for call in release.call_args_list])

    def test_matrix_continues_test_failures_but_stops_cleanup_failures(self):
        devices = m.device_matrix('all')[:2]
        for cleanup_fails in [False, True]:
            with tempfile.TemporaryDirectory() as root, patch.dict(os.environ), \
                    patch.object(m, 'ROOT', Path(root)), \
                    patch.object(m, 'device_matrix', return_value=devices), \
                    patch.object(m, 'acquire') as acquire, \
                    patch.object(m, 'run_tests', side_effect=[RuntimeError('test failed'), None]), \
                    patch.object(m, 'release', side_effect=RuntimeError('cleanup failed') if cleanup_fails else None):
                with self.assertRaises(RuntimeError):
                    m.run_matrix(None, Path(root) / '.selectel/lease.json', 'all')
                self.assertEqual(1 if cleanup_fails else 2, acquire.call_count)
                report = json.loads((Path(root) / 'app/build/reports/selectel/matrix.json').read_text())
                self.assertEqual('failed', report[0]['status'])
                if not cleanup_fails:
                    self.assertEqual('passed', report[1]['status'])
                    self.assertNotEqual(acquire.call_args_list[0].args[1], acquire.call_args_list[1].args[1])


if __name__ == '__main__':
    unittest.main()
