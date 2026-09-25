# Source compatibility tests

This JVM-only module compares synthetic source fixtures with the production rule,
network and import modules and a pinned Legado selector reference. The application
does not depend on this test module.

Run `./gradlew :source-compatibility:test` from the repository root. Reports are
written under this module's `build/` directory. Other product tests are run by the
JVM and Android CI workflows; the fixture coverage map only checks their references.

`browser/` and `fixtures/native-routing/site/` contain resources used by JVM and
Android tests. Research reports, private source inventories and device captures
belong outside version control.

The reference source and its license are documented in [reference/NOTICE.md](reference/NOTICE.md).

## Stateful Android acceptance

`PixivLifecycleInstrumentedTest` is an offline, synthetic integration fixture, not
a replay of Pixiv responses. It uses the production isolated Worker/Broker, encrypted
account storage and a disk-backed Room database. It covers login, discovery, search,
standalone/series identity, ordered text/image content, reopening the settings panel,
component reconstruction with saved progress and a server-issued persistent login cookie,
cancellation, logout and another account. Session cookies remain memory-only.
Component reconstruction is not an assertion that the Android OS killed/restarted the app.

Run it with `:app:connectedDebugAndroidTest` and
`-Pandroid.testInstrumentationRunnerArguments.class=indi.renakoni.nextvol.sourceexecution.PixivLifecycleInstrumentedTest`.
Keep `SourceCompatibilityInstrumentedTest` and `IsolatedExecutionInstrumentedTest`
in the regression selection for existing source dialects, error layering and cancellation.
The default device CI includes the synthetic lifecycle and compatibility tests; the
private original-source audit is deliberately not part of its required selection.
`SourceCatalogTest` separately checks all addable bundled definitions through the default
importer and activation/restoration; it does not assert that each remote website is reachable.

`PixivOriginalImportInstrumentedTest` is an opt-in local audit. It requires
`-Pandroid.testInstrumentationRunnerArguments.pixivOriginal=true` and a privately
provisioned `files/pixiv-original-private.json` in the target debug app. The test checks
the exact v284 SHA-256 before importing with the draft UI's default `AUTO_PROFILE`,
grants only an unrelated `.invalid` origin (registration requires a nonempty grant
list), and tests both novel panels plus the
main source's unmodified discovery catalogue. Neither website origin is granted.
In this hash-pinned sample the backup has an `exploreUrl` but both source and discovery
are disabled by default; the panel audit explicitly enables the source without
changing its scripts or enabling discovery. A disabled capability is not a missing rule.
The private source, account data and site captures must never enter this repository.

Neither test proves authenticated Pixiv readability. A live acceptance record must
identify the source hash, commit and APK hash and separately record import, discovery,
login, actual standalone/series work, ordered body/images, restart/progress and logout.
Leave untested stages incomplete. E/MD3 source inspection and the pinned selector
reference suite are not full E/MD3 device-lifecycle comparisons.

## Live Pixiv acceptance — 2026-09-25

This record uses the original v284 input with SHA-256
`9b5fde27e9a6f425a5067a52b2f8ec8082b258a27e968dd637a9d87df45b65d1`.
The debug application was built from integration commit `4d57caec` plus the fixes
recorded in `974b5511` (#427), `9e905d49` (#428), `ec4dafc2` (#429),
`f44f3eb5` (#430), `cbdbcdc0` (#431), and `47ecb510` (#432). Its APK SHA-256 is
`e0e988cdb003f83a9bcb4dbad184f996444445fd9709b8972148b9a633300059`.
Private probes used the production isolated runner, encrypted account store and
application database. No account credentials, private source JSON, page bodies,
screenshots or probe files are included here.

| Stage | Observed result |
| --- | --- |
| Original import | Both novel definitions were imported through the normal UI; the manga definition was skipped. |
| Original discovery | Main catalogue returned 30 navigation rows, not 30 books. |
| Login persistence | Saved session and CSRF cache restored after replacement installation without entering credentials again. |
| Original live search | Returned 62 works: 38 series candidates and 24 standalone candidates. |
| Original series sample | Details, 9-chapter directory, first chapter with 7,697 text characters, and cover image request succeeded. |
| Original standalone sample | Details, single-chapter directory, 2,732 text characters, and cover image request succeeded. |
| Body images | Neither selected chapter contained inline images. Real cover requests passed; text/image ordering remains covered by the synthetic lifecycle fixture, not by these two live chapters. |
| Process restart | After an explicit Android force-stop, the saved session, actual Room reading position and real search restored successfully. |
| Backup source | Its original login/settings panel executed successfully. Its discovery rule exists but is disabled by default; it is not a missing-rule case. |
| Account settings | The original warm settings page became readable after the transport decoding fix. Removing only its cached header object reproduced a separate original-script failure. |
| Corrected settings script | Only the null-header fallback and the Cookie/User-Agent typo were corrected in both installed novel definitions through the production revision service. Original revisions were retained and the account generation did not change. The cold account settings page then loaded, and returning preserved the session. The original input file is unchanged. |
| Logout | The isolated synthetic device lifecycle verifies logout/account retirement; a live logout followed by reauthentication was not performed, to leave the user's working session intact. |

All original-source search/reading/restart results above were captured **before**
applying the two settings-script corrections. They must not be described as proof
that the unmodified source's cold account-settings action is correct.

The integrated JVM reports contain 102 network, 158 Rhino, 118 execution and 180
content tests with no failures or skips. Four targeted device regressions passed:
native login completion, waiting beyond one minute, cancellation/owner isolation,
and the synthetic reading/progress/logout lifecycle. These are separate from the
private real-site observations. No full E/MD3 device comparison was performed.

Issue #388 and its draft PR remain open pending the unperformed live logout and
reauthentication check. This record does not convert partial acceptance into a
completed lifecycle claim.
