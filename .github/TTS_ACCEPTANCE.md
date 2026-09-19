# Background read-aloud acceptance

`ReadAloudBackgroundInstrumentedTest` imports an actual TXT book through
`LocalBookStore` and uses the production chapter repository, synthesizer, media
service, player and listening bookmarks. Supply an installed, initialized system
TTS engine on a dedicated device. Installing an engine APK alone does not prove
its voice data is ready.

The three short tests cover:

- Home, screen off and removal of the reader activity's task while audio advances;
  natural entry into the next chapter and completion of the last chapter.
- Actions from the actual media notification: pause without advancing audio,
  resume in the foreground service, and Stop removing the service, notification
  and temporary audio. These invoke the notification's pending intents; they do
  not verify the visual arrangement of System UI controls.
- Stop followed by a new playback service: each instance owns a distinct media
  notification key and keeps playing while System UI removes the previous one.
  Reusing a constant ID let Android 15's asynchronous dismissal address the new
  session and send it Stop. The test checks identity and actual audio advancement.

The fixture restores the previous speech settings and removes its imported book,
listening bookmark and temporary files. It does not use an in-memory replacement
for the chapter repository or test tones in place of speech.

After building and installing the debug app and AndroidTest APKs, run:

```sh
adb -s DEVICE shell am instrument -w -r -e speechEngine com.reecedunn.espeak -e class indi.renakoni.nextvol.tts.ReadAloudBackgroundInstrumentedTest indi.renakoni.nextvol.debug.test/androidx.test.runner.AndroidJUnitRunner
```

On API 33+, run the short tests with `POST_NOTIFICATIONS` both granted and denied,
and restore the initial permission afterwards. The app already declares the
media-playback foreground service and requests notification permission; tests
must establish which behavior needs changing before adding another prompt.

App screenshots for frontend review must come from MuMu. If no connected MuMu
instance is available, ask the user to start it. API/AVD devices remain valid
for automated functional tests; their screenshots and JVM renders do not
replace MuMu visual acceptance. Broader visual changes await user feedback.

## Continuous playback

The optional long test imports a 600-chapter book, moves to Home and turns the
screen off. It observes chapter order, foreground-service lifetime, playback
advancement and the bound of four synthesized WAV files. It fails if playback
stops advancing for 60 seconds. This exercises service scheduling and playback;
it does not perform speech recognition to compare the spoken words.

```sh
adb -s DEVICE shell am instrument -w -r -e speechEngine com.reecedunn.espeak -e speechLongRunMinutes 120 -e class indi.renakoni.nextvol.tts.ReadAloudBackgroundInstrumentedTest#continuousBackgroundPlaybackStaysOrderedAndBounded indi.renakoni.nextvol.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Minute samples are written to the app's external-files directory as
`tts-background-long.jsonl`. Save those samples, the instrumentation result,
device/API, engine version and permission state with the acceptance record.
A one-minute diagnostic run verifies the harness; it does not satisfy the
two-hour continuous-playback acceptance requirement. Do not install another APK
or run another instrumentation suite on the same device during the long run.

CI includes this class but skips real-engine tests when `speechEngine` is absent;
the long test additionally requires `speechLongRunMinutes`. CI green alone is
therefore insufficient evidence for device acceptance. Existing session tests
separately cover next-chapter failure/retry, cancellation, switching books,
bounded prefetch and durable progress.

## Permission design references

- [Media3 background playback](https://developer.android.com/media/media3/session/background-playback):
  playback belongs to the service and active playback may survive task removal.
- [Media playback foreground-service type](https://developer.android.com/develop/background-work/services/fgs/service-types#media):
  the manifest permission is required; this type has no extra runtime permission.
- [Notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission):
  media-session notifications have an exemption from the ordinary permission behavior.
- [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby):
  determine whether power management affects the core function before requesting
  exemption or directing the reader to the corresponding system setting.
- [Legado with MD3](https://github.com/HapeLee/legado-with-MD3): its other-settings
  route exposes an explicit battery-permission request; `SystemUtils` checks the
  exemption state before opening the system request. Treat this as a reference
  for an optional settings flow, not a requirement to copy its permission list.

These checks do not establish behavior for every OEM, physical headset or
Bluetooth device, or prove availability of an external HTTP speech provider.
Use the platform contract, mature player implementations and actual device
evidence together when deciding whether additional permission guidance is needed.
