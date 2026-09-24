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
