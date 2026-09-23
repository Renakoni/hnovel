import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


WRAPPER = Path(__file__).with_name('emulator-tests.sh').resolve()
REPORT_PULL = Path(__file__).with_name('pull-native-environment.sh').resolve()


class EmulatorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='emulator-wrapper-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.script('timeout', '#!/usr/bin/env bash\nshift\nexec "$@"\n')
        self.script('sleep', '#!/usr/bin/env bash\nexit 0\n')
        self.script('adb', '''#!/usr/bin/env bash
set -eu
echo "$*" >> "$EMULATOR_CASE_ROOT/adb-calls"
if [[ "$EMULATOR_CASE" == diagnostics-fail && -f "$EMULATOR_CASE_ROOT/ran" ]]; then exit 23; fi
case "$*" in
  'logcat -b crash -b events -b system -v threadtime')
    echo $$ > "$EMULATOR_CASE_ROOT/logcat-pid"
    echo 'AndroidRuntime: FATAL EXCEPTION: main'
    touch "$EMULATOR_CASE_ROOT/stream-ready"
    if [[ "$EMULATOR_CASE" == stream-fails ]]; then exit 23; fi
    exec /bin/sleep 60 ;;
  'wait-for-device')
    if [[ "$EMULATOR_CASE" == report-offline || "$EMULATOR_CASE" == report-recovers && ! -f "$EMULATOR_CASE_ROOT/reconnected" ]]; then exit 17; fi ;;
  'reconnect offline')
    if [[ "$EMULATOR_CASE" == report-reconnect-fails ]]; then exit 23; fi
    touch "$EMULATOR_CASE_ROOT/reconnected" ;;
  'pull /sdcard/Android/data/indi.renakoni.nextvol.debug/files/native-environment.json '* )
    if [[ "$EMULATOR_CASE" == report-offline || "$EMULATOR_CASE" == report-recovers && ! -f "$EMULATOR_CASE_ROOT/reconnected" ]]; then exit 17; fi
    if [[ "$EMULATOR_CASE" == report-missing ]]; then exit 1; fi
    if [[ "$EMULATOR_CASE" == report-disconnects || "$EMULATOR_CASE" == report-reconnect-fails ]]; then
      if [[ ! -f "$EMULATOR_CASE_ROOT/pulled" ]]; then
        touch "$EMULATOR_CASE_ROOT/pulled"
        exit 17
      fi
    fi
    echo '[]' > "$3" ;;
  'shell pm path android')
    if [[ "$EMULATOR_CASE" == offline ]]; then exit 17; fi
    echo package:/system/framework/framework-res.apk ;;
  'shell getprop ro.build.version.sdk')
    if [[ "$EMULATOR_CASE" == api-query-fails ]]; then exit 17; fi
    if [[ "$EMULATOR_CASE" == messaging-* ]]; then echo 24; else echo 35; fi ;;
  'shell pm list packages com.google.android.apps.messaging')
    if [[ "$EMULATOR_CASE" != messaging-missing ]]; then echo package:com.google.android.apps.messaging; fi ;;
  'shell pm disable-user --user 0 com.google.android.apps.messaging')
    if [[ "$EMULATOR_CASE" == messaging-disable-fails ]]; then exit 19; fi
    touch "$EMULATOR_CASE_ROOT/messaging-disabled" ;;
  'shell am force-stop com.google.android.apps.messaging')
    if [[ "$EMULATOR_CASE" == messaging-stop-fails ]]; then exit 19; fi
    touch "$EMULATOR_CASE_ROOT/messaging-stopped" ;;
  'shell pm list packages com.google.android.apps.nexuslauncher')
    if [[ "$EMULATOR_CASE" == boot-diagnostics-offline ]]; then exit 17; fi
    if [[ "$EMULATOR_CASE" != no-launcher ]]; then echo package:com.google.android.apps.nexuslauncher; fi ;;
  'shell am force-stop com.google.android.apps.nexuslauncher')
    if [[ "$EMULATOR_CASE" == stop-fails ]]; then exit 19; fi
    touch "$EMULATOR_CASE_ROOT/stopped" ;;
  'shell dumpsys window windows')
    if [[ "$EMULATOR_CASE" == messaging-stuck || "$EMULATOR_CASE" == messaging-ready && ! -f "$EMULATOR_CASE_ROOT/messaging-stopped" ]]; then
      echo 'Window{789 u0 Application Error: com.google.android.apps.messaging}'
    elif [[ "$EMULATOR_CASE" == stuck || ! -f "$EMULATOR_CASE_ROOT/stopped" && "$EMULATOR_CASE" != no-launcher ]]; then
      echo 'Window{123 u0 Application Not Responding: com.google.android.apps.nexuslauncher}'
    elif [[ "$EMULATOR_CASE" == delayed && ! -f "$EMULATOR_CASE_ROOT/probed" ]]; then
      touch "$EMULATOR_CASE_ROOT/probed"
      echo 'Window{123 u0 Application Not Responding: com.google.android.apps.nexuslauncher}'
    elif [[ "$EMULATOR_CASE" == app-error ]]; then
      echo 'Window{456 u0 Application Not Responding: indi.renakoni.nextvol.debug}'
    fi ;;
  'logcat -d -v threadtime')
    echo 'ActivityManager: ANR in com.google.android.apps.nexuslauncher'
    if [[ "$EMULATOR_CASE" == boot-diagnostics-* ]]; then exit 255; fi ;;
esac
''')
        self.script('test-command', '''#!/usr/bin/env bash
if [[ "$EMULATOR_CASE" == stream-* ]]; then
  for attempt in {1..100}; do
    [[ -f "$EMULATOR_CASE_ROOT/stream-ready" ]] && break
    /bin/sleep 0.01
  done
fi
if [[ "$EMULATOR_CASE" == messaging-ready ]]; then
  test -f "$EMULATOR_CASE_ROOT/messaging-disabled" && test -f "$EMULATOR_CASE_ROOT/messaging-stopped" || exit 91
fi
printf '%s\n' "$@" >> "$EMULATOR_CASE_ROOT/ran"
exit "$EMULATOR_TEST_EXIT"
''')

    def script(self, name, content):
        path = self.bin / name
        path.write_text(content, encoding='utf-8', newline='\n')
        path.chmod(0o755)

    def run_case(self, case, code=0):
        env = dict(os.environ, EMULATOR_CASE=case, EMULATOR_TEST_EXIT=str(code),
                   EMULATOR_CASE_ROOT=self.root.as_posix())
        env['PATH'] = str(self.bin) + os.pathsep + env['PATH']
        self.result = subprocess.run([shutil.which('bash'), WRAPPER.as_posix(), 'test-command', 'one argument', 'second'],
                                     cwd=self.root, env=env, capture_output=True, text=True, timeout=20)
        self.diagnostics = self.root / 'app/build/reports/emulator-diagnostics'
        return self.result.returncode

    def run_report_case(self, case):
        env = dict(os.environ, EMULATOR_CASE=case, EMULATOR_CASE_ROOT=self.root.as_posix())
        env['PATH'] = str(self.bin) + os.pathsep + env['PATH']
        self.result = subprocess.run([shutil.which('bash'), REPORT_PULL.as_posix()],
                                     cwd=self.root, env=env, capture_output=True, text=True, timeout=20)
        self.report = self.root / 'app/build/outputs/androidTest-results/connected/native-environment.json'
        return self.result.returncode

    def test_native_report_is_collected_without_reconnecting_a_ready_device(self):
        self.assertEqual(0, self.run_report_case('report-ready'), self.result.stderr)
        self.assertEqual('[]', self.report.read_text().strip())
        self.assertNotIn('reconnect', (self.root / 'adb-calls').read_text())

    def test_native_report_recovers_when_device_is_offline_after_tests(self):
        self.assertEqual(0, self.run_report_case('report-recovers'), self.result.stderr)
        self.assertEqual('[]', self.report.read_text().strip())

    def test_native_report_retries_disconnect_during_pull(self):
        self.assertEqual(0, self.run_report_case('report-disconnects'), self.result.stderr)
        self.assertEqual('[]', self.report.read_text().strip())

    def test_native_report_still_fails_if_device_never_reconnects(self):
        self.assertEqual(1, self.run_report_case('report-offline'))
        calls = (self.root / 'adb-calls').read_text().splitlines()
        self.assertEqual(3, calls.count('wait-for-device'))
        self.assertEqual(2, calls.count('reconnect offline'))
        self.assertFalse(self.report.exists())

    def test_native_report_still_fails_if_required_file_is_missing(self):
        self.assertEqual(1, self.run_report_case('report-missing'))
        calls = (self.root / 'adb-calls').read_text().splitlines()
        self.assertEqual(3, sum(call.startswith('pull ') for call in calls))
        self.assertFalse(self.report.exists())

    def test_native_report_can_recover_even_if_reconnect_command_fails(self):
        self.assertEqual(0, self.run_report_case('report-reconnect-fails'), self.result.stderr)
        self.assertEqual('[]', self.report.read_text().strip())

    def test_boot_anr_is_cleared_before_the_original_command(self):
        self.assertEqual(0, self.run_case('launcher'), self.result.stderr)
        self.assertEqual(['one argument', 'second'], (self.root / 'ran').read_text().splitlines())
        calls = (self.root / 'adb-calls').read_text()
        self.assertEqual(1, calls.count('shell am force-stop com.google.android.apps.nexuslauncher'))
        self.assertIn('ANR in com.google.android.apps.nexuslauncher', (self.diagnostics / 'boot-logcat.txt').read_text())
        self.assertNotIn('Application Not Responding', (self.diagnostics / 'windows-before-tests.txt').read_text())

    def test_anr_window_removal_can_finish_after_force_stop_returns(self):
        self.assertEqual(0, self.run_case('delayed'), self.result.stderr)
        self.assertTrue((self.root / 'probed').exists())
        self.assertTrue((self.root / 'ran').exists())

    def test_missing_google_launcher_does_not_block_other_images(self):
        self.assertEqual(0, self.run_case('no-launcher'), self.result.stderr)
        self.assertNotIn('force-stop', (self.root / 'adb-calls').read_text())

    def test_api24_messaging_is_disabled_and_stopped_before_tests(self):
        self.assertEqual(0, self.run_case('messaging-ready'), self.result.stderr)
        self.assertEqual(['one argument', 'second'], (self.root / 'ran').read_text().splitlines())
        self.assertNotIn('Application Error', (self.diagnostics / 'windows-before-tests.txt').read_text())

    def test_api24_without_messaging_never_disables_a_package(self):
        self.assertEqual(0, self.run_case('messaging-missing'), self.result.stderr)
        calls = (self.root / 'adb-calls').read_text()
        self.assertNotIn('disable-user', calls)
        self.assertNotIn('force-stop com.google.android.apps.messaging', calls)

    def test_api35_does_not_modify_messaging(self):
        self.assertEqual(0, self.run_case('launcher'), self.result.stderr)
        self.assertNotIn('com.google.android.apps.messaging', (self.root / 'adb-calls').read_text())

    def test_failed_api_query_does_not_skip_preparation_and_start_tests(self):
        self.assertEqual(17, self.run_case('api-query-fails'))
        self.assertFalse((self.root / 'ran').exists())

    def test_failed_messaging_preparation_blocks_tests(self):
        for case in ['messaging-disable-fails', 'messaging-stop-fails']:
            with self.subTest(case=case):
                self.assertEqual(19, self.run_case(case))
                self.assertFalse((self.root / 'ran').exists())

    def test_remaining_messaging_crash_dialog_blocks_tests(self):
        self.assertEqual(1, self.run_case('messaging-stuck'))
        self.assertFalse((self.root / 'ran').exists())
        self.assertIn('tests were not started', self.result.stderr)

    def test_api24_test_failure_survives_messaging_preparation(self):
        self.assertEqual(42, self.run_case('messaging-ready', 42))
        self.assertTrue((self.root / 'ran').exists())

    def test_test_failure_remains_a_failure_after_diagnostics(self):
        self.assertEqual(42, self.run_case('launcher', 42))
        for name in ['logcat', 'windows', 'activities', 'packages', 'storage']:
            self.assertTrue((self.diagnostics / f'{name}.txt').exists(), name)

    def test_diagnostic_failure_does_not_replace_test_exit_code(self):
        self.assertEqual(42, self.run_case('diagnostics-fail', 42))

    def test_crash_events_survive_missing_report_and_logcat_process_is_stopped(self):
        self.assertEqual(42, self.run_case('stream-crash', 42))
        self.assertIn('FATAL EXCEPTION', (self.diagnostics / 'runtime-events.txt').read_text())
        pid = int((self.root / 'logcat-pid').read_text())
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)

    def test_stream_disconnect_does_not_replace_test_failure(self):
        self.assertEqual(42, self.run_case('stream-fails', 42))

    def test_boot_logcat_failure_still_runs_the_original_command_once(self):
        self.assertEqual(0, self.run_case('boot-diagnostics-fail'), self.result.stderr)
        self.assertEqual(['one argument', 'second'], (self.root / 'ran').read_text().splitlines())
        self.assertIn('Boot logcat capture failed', self.result.stderr)
        self.assertIn('ANR in com.google.android.apps.nexuslauncher', (self.diagnostics / 'boot-logcat.txt').read_text())

    def test_boot_logcat_failure_does_not_hide_test_failure(self):
        self.assertEqual(42, self.run_case('boot-diagnostics-fail', 42))
        self.assertTrue((self.root / 'ran').exists())

    def test_adb_still_offline_after_boot_logcat_failure_blocks_tests(self):
        self.assertEqual(17, self.run_case('boot-diagnostics-offline'))
        self.assertFalse((self.root / 'ran').exists())

    def test_remaining_launcher_dialog_fails_before_tests(self):
        self.assertEqual(1, self.run_case('stuck'))
        self.assertFalse((self.root / 'ran').exists())
        self.assertIn('tests were not started', self.result.stderr)

    def test_adb_failure_does_not_start_tests(self):
        self.assertEqual(17, self.run_case('offline'))
        self.assertFalse((self.root / 'ran').exists())

    def test_failed_launcher_reset_does_not_start_tests(self):
        self.assertEqual(19, self.run_case('stop-fails'))
        self.assertFalse((self.root / 'ran').exists())

    def test_app_error_dialog_is_not_dismissed_or_treated_as_success(self):
        self.assertEqual(42, self.run_case('app-error', 42))
        self.assertIn('indi.renakoni.nextvol.debug', (self.diagnostics / 'windows.txt').read_text())
        self.assertNotIn('force-stop indi.renakoni', (self.root / 'adb-calls').read_text())


if __name__ == '__main__':
    unittest.main()
