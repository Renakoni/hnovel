# Android checks

Pull requests, merge groups, and manual runs use four independent workflows:

| Workflow | Checks | Reports |
| --- | --- | --- |
| `unit-tests.yml` (1/4) | `JVM unit tests` | `unit-test-reports` |
| `device-tests.yml` (2/4) | `Isolated execution (API 24)` and `Isolated execution (API 35)` | `execution-platform-api-24`, `execution-platform-api-35` |
| `release-checks.yml` (3/4) | `Release APK` and `Minified source runtime (API 35)` | `release-build-reports`, `minified-source-runtime` |
| `lint.yml` (4/4) | `Android Lint` | `android-lint-reports` |

The JVM check no longer waits for the release APK build. Device tests retain the
API matrix, test classes, and diagnostic reports. The minified check still builds
the benchmark variant and exercises the source runtime and read-aloud service.
The workflows have separate concurrency groups so they cannot cancel each other.

Require all six check names above in the `main` branch's status checks. In
particular, `Release APK` must be required separately now that it is outside the
JVM job. Keep strict up-to-date checking and the other branch protections enabled.
YAML alone does not update repository protection settings.

## Shared Gradle cache

`gradle-cache.yml` writes caches from `main` when build configuration, dependencies,
the wrapper, or workflow files change. It can also be dispatched manually on
`main` after eviction or when investigating cache misses. It compiles the debug
app and its test APKs without repeating the test suites.

`gradle/actions/setup-gradle` owns the cache, including wrapper distributions,
dependencies, transformed artifacts, and Gradle's local build cache. It stores
wrapper distributions as separate reusable entries referenced by the Gradle
User Home metadata; an additional `actions/cache` for `wrapper/dists` would
duplicate that storage. Cache restore falls back across jobs on the same runner
OS and architecture, so all four workflows can use the main cache.

PR and merge-group checks are explicitly read-only. GitHub allows them to restore
the default branch's cache, while caches written under a PR merge ref are only
useful for reruns of that same PR. The warm workflow only runs on `main`; it does
not execute PR code with cache write access.

The first run before main has a cache, an evicted cache, and newly introduced
dependencies can still require downloads. Release-only work and test execution
remain in their own checks. Splitting workflows improves feedback and targeted
reruns; it does not eliminate each runner's build or promise a fixed speedup.

## Lint and translation coverage

`Android Lint` runs the standard-library resource contract tests/checker and
`:app:lintDebug` with the repository wrapper and JDK 22. No emulator is required.
Lint still runs after a resource-check failure to collect its diagnostics. Bash
pipefail preserves failures while tee records output; neither step allows errors
to become a successful check. HTML/XML/text reports and logs upload with
`if: always()`, including failing runs. Setup failures before analysis may leave
no Lint report; the Actions step log remains the evidence in that case.

| Scope | Coverage |
| --- | --- |
| App Debug | Full Android Lint, including main/Debug resources and the configured test source analysis |
| App translations | Default/en, Simplified Chinese, Traditional Chinese, generic Russian and existing ru-RU overrides; see [resource contracts](RESOURCE_CONTRACTS.md) |
| `:api` | Dependency model/class information used by app analysis; no independent `:api:lintDebug` scan |
| `:plugin:js` | Separate Android app; not included in this check |
| `:benchmark` | Android test module, exercised by the existing minified device job; not independently linted |
| App Release/snapshot/benchmark | No full variant Lint here; Release assembly/lintVital and minified runtime remain separate checks |
| JVM source/EPUB/compiler modules | Existing JVM/build checks; not independent Android Lint targets |

The gate fails on Error/Fatal diagnostics outside the reviewed baseline. Warnings
remain visible without requiring a mechanical cleanup of all existing warnings.
See [Lint triage](LINT.md) for exact deferred locations and removal conditions.
Do not regenerate a baseline automatically in CI, globally disable i18n/API/back
checks, or add a known crash/data-loss defect to it. `LintBaselineFixed` is fatal:
when a deferred finding is fixed, remove its obsolete baseline entry too.

The Lint concurrency key includes the event and PR/ref, keeping PR, merge-group,
and manual runs independent from one another and from the other three workflows.
Configure `Android Lint` as required only after a real PR has passed; read back
the protection settings and retain the existing five checks and strict mode.

## Maintenance

After workflow changes, run `actionlint` and the affected checks. For cache issues,
inspect setup-gradle's cache report before scheduling another run. Generated test
reports belong in Actions artifacts or local build directories, not in Git.
