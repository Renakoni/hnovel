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
