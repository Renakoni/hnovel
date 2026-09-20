import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


WRAPPER = Path(__file__).with_name('emulator-tests.sh').resolve()


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
  'shell pm path android')
    if [[ "$EMULATOR_CASE" == offline ]]; then exit 17; fi
    echo package:/system/framework/framework-res.apk ;;
  'shell pm list packages com.google.android.apps.nexuslauncher')
    if [[ "$EMULATOR_CASE" != no-launcher ]]; then echo package:com.google.android.apps.nexuslauncher; fi ;;
  'shell am force-stop com.google.android.apps.nexuslauncher')
    if [[ "$EMULATOR_CASE" == stop-fails ]]; then exit 19; fi
    touch "$EMULATOR_CASE_ROOT/stopped" ;;
  'shell dumpsys window windows')
    if [[ "$EMULATOR_CASE" == stuck || ! -f "$EMULATOR_CASE_ROOT/stopped" && "$EMULATOR_CASE" != no-launcher ]]; then
      echo 'Window{123 u0 Application Not Responding: com.google.android.apps.nexuslauncher}'
    elif [[ "$EMULATOR_CASE" == delayed && ! -f "$EMULATOR_CASE_ROOT/probed" ]]; then
      touch "$EMULATOR_CASE_ROOT/probed"
      echo 'Window{123 u0 Application Not Responding: com.google.android.apps.nexuslauncher}'
    elif [[ "$EMULATOR_CASE" == app-error ]]; then
      echo 'Window{456 u0 Application Not Responding: indi.renakoni.nextvol.debug}'
    fi ;;
  'logcat -d -v threadtime') echo 'ActivityManager: ANR in com.google.android.apps.nexuslauncher' ;;
esac
''')
        self.script('test-command', '''#!/usr/bin/env bash
printf '%s\n' "$@" > "$EMULATOR_CASE_ROOT/ran"
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

    def test_test_failure_remains_a_failure_after_diagnostics(self):
        self.assertEqual(42, self.run_case('launcher', 42))
        for name in ['logcat', 'windows', 'activities', 'packages', 'storage']:
            self.assertTrue((self.diagnostics / f'{name}.txt').exists(), name)

    def test_diagnostic_failure_does_not_replace_test_exit_code(self):
        self.assertEqual(42, self.run_case('diagnostics-fail', 42))

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
