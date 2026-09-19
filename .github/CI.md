# Android checks

Pull requests, merge groups, and manual runs use three independent workflows:

| Workflow | Checks | Reports |
| --- | --- | --- |
| `unit-tests.yml` (1/3) | `JVM unit tests` | `unit-test-reports` |
| `device-tests.yml` (2/3) | `Isolated execution (API 24)` and `Isolated execution (API 35)` | `execution-platform-api-24`, `execution-platform-api-35` |
| `release-checks.yml` (3/3) | `Release APK` and `Minified source runtime (API 35)` | `release-build-reports`, `minified-source-runtime` |

The JVM check no longer waits for the release APK build. Device tests retain the
API matrix, test classes, and diagnostic reports. The minified check still builds
the benchmark variant and exercises the source runtime and read-aloud service.
The workflows have separate concurrency groups so they cannot cancel each other.

Require all five check names above in the `main` branch's status checks. In
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
OS and architecture, so all three workflows can use the main cache.

PR and merge-group checks are explicitly read-only. GitHub allows them to restore
the default branch's cache, while caches written under a PR merge ref are only
useful for reruns of that same PR. The warm workflow only runs on `main`; it does
not execute PR code with cache write access.

The first run before main has a cache, an evicted cache, and newly introduced
dependencies can still require downloads. Release-only work and test execution
remain in their own checks. Splitting workflows improves feedback and targeted
reruns; it does not eliminate each runner's build or promise a fixed speedup.

## Maintenance

After workflow changes, run `actionlint` and the affected checks. For cache issues,
inspect setup-gradle's cache report before scheduling another run. Generated test
reports belong in Actions artifacts or local build directories, not in Git.
