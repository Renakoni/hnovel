# Source execution isolation (#86, in progress)

`IsolatedExecutor` creates a fresh worker JVM for each invocation and sends only a serialized, host-issued identity, task and limits. The host locates the worker classpath from actual code sources, enforces a wall-clock deadline, watches host revocation while the worker runs, forcibly terminates late workers and rejects results after revocation. `ExecutionAuthority` issues unguessable nonces and revokes identities; forged identities are rejected.

The Android app uses a non-exported `isolatedProcess` service with an AIDL request/callback protocol. The service checks the calling UID inside Binder transactions against the installed package UID, never against `onBind`'s calling UID or an Intent extra. Callbacks are checked against the worker UID. A debug-only second isolated service verifies that forwarding the worker Binder does not grant access to another UID.

`AndroidIsolatedExecutor` requires a host `ExecutionAuthority`, binds each request to a ticket and rejects revoked or forged identities. One invocation runs at a time across executor instances; contention returns `Busy`. Requests and serialized responses are capped at 256 KiB, with the requested output limit checked again by the host. There is no worker queue. Timeout, caller cancellation and revocation retire the process, and every successful invocation also retires it so engine globals cannot survive. The host waits for Binder death before allowing reuse; a still-live retiring Binder prevents another invocation. Source identity is not chosen by a browser tab.

The Application skips Hilt and host/plugin initialization in isolated UIDs. Both service process names use Android-compatible underscores; hyphens caused an APK installation failure on API 24 despite successful compilation.

## Verification

`./gradlew :source-execution:test :app:testDebugUnitTest` covers JVM protocol/host regressions. Real Android tests run with:

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=indi.dmzz_yyhyy.lightnovelreader.sourceexecution.IsolatedExecutionInstrumentedTest
```

The existing PR workflow includes API 24 and API 35 emulator jobs. They verify remote Binder transport, independent UID, rejection of a forwarded Binder from a foreign isolated UID, input-size rejection, timeout termination, forged identity rejection, revocation, cancellation and a subsequent successful source invocation. These tests execute the actual app Application and services; they are not Robolectric tests.

## Remaining Issue #86 acceptance work

The worker still supports only the protocol's Echo/Sleep tasks. This PR has not yet connected the rule/Rhino engine or the source-network broker to Android IPC. Native allocation limits, catastrophic regex/infinite JavaScript tests, broker side-effect revocation and denial of engine-level reflection/network/file escape attempts remain required before claiming full Issue #86 completion. A manifest, ClassShutter or successful transport test alone is not evidence for those properties.
