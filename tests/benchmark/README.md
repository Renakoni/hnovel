# Android UI and performance tests

This test-only module uses UI Automator and Macrobenchmark with the application's
benchmark variant. It covers reader, bookshelf, import/export, settings, source
runtime and listening flows. CI runs the minified source runtime and TTS smoke tests.

From the repository root, on a dedicated test device:

```sh
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

Functional tests clear the benchmark application's data and install their fixtures.
Keep personal data off that test application. Live-source tests need network access;
performance thresholds require a stable physical device.

Reports and traces are generated under `tests/benchmark/build/` and are not committed.
