# Source execution isolation (#86, in progress)

`IsolatedExecutor` creates a fresh worker JVM for each invocation and sends only a serialized, host-issued identity, task and limits. The host locates the worker classpath from actual code sources, enforces a wall-clock deadline, watches host revocation while the worker runs, forcibly terminates late workers and rejects results after revocation. `ExecutionAuthority` issues unguessable nonces and revokes identities; forged identities are rejected.

The Android app uses a non-exported `isolatedProcess` service with an AIDL request/callback protocol. The service checks the calling UID inside Binder transactions against the installed package UID, never against `onBind`'s calling UID or an Intent extra. Callbacks are checked against the worker UID. A debug-only second isolated service verifies that forwarding the worker Binder does not grant access to another UID.

`AndroidIsolatedExecutor` requires a host `ExecutionAuthority`, binds each request to a ticket and rejects revoked or forged identities. One invocation runs at a time across executor instances; contention returns `Busy`. Requests and serialized responses are capped at 256 KiB, with the requested output limit checked again by the host. There is no worker queue. Timeout, caller cancellation and revocation retire the process, and every successful invocation also retires it so engine globals cannot survive. The host waits for Binder death before allowing reuse; a still-live retiring Binder prevents another invocation. Source identity is not chosen by a browser tab.

The Application skips Hilt and host/plugin initialization in isolated UIDs. Both service process names use Android-compatible underscores; hyphens caused an APK installation failure on API 24 despite successful compilation.

`ExecutionTask.Script` executes Rhino 1.8.1 inside the worker with JSON input/output and source globals derived from the host-issued identity. A reverse AIDL broker accepts only an operation and bounded JSON arguments. Its host endpoint authenticates the calling worker UID and the live invocation before dispatch. The worker receives no SourceSession, OkHttp client, storage path or credential manager.

`SourceExecutionBroker` binds namespace, source, profile and account generation to one existing host session, plus the invocation request budget and request context. It currently implements synchronous `java.ajax`, source configuration and cache operations. These go through the existing network/storage policies. It is owned by the invocation and cancelled on retirement; closing it does not erase the shared source session. Request dispatch and local Cookie/cache/storage commits are serialized with identity revocation, so a revoked execution cannot commit a late response. Already-dispatched HTTP requests cannot be recalled from a remote server.

## Verification

`./gradlew :source-execution:test :app:testDebugUnitTest` covers JVM protocol/host regressions. Real Android tests run with:

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=indi.dmzz_yyhyy.lightnovelreader.sourceexecution.IsolatedExecutionInstrumentedTest
```

The existing PR workflow includes API 24 and API 35 emulator jobs. They verify remote Binder transport, independent UID, rejection of a forwarded Binder from a foreign isolated UID, input-size rejection, timeout termination, forged identity rejection, revocation, cancellation and a subsequent successful source invocation. These tests execute the actual app Application and services; they are not Robolectric tests.

The integrated suite additionally runs a real Rhino Ajax request through reverse Binder to a local server, checks source storage isolation, interrupts a catastrophic regex, and cancels an Ajax call while the broker has only one request permit. The next invocation must acquire that permit and complete. The pure-tool test runs 470 expressions in the isolated worker: all 32 Base64 flag combinations across seven inputs are compared to Android's actual encoder/decoder, malformed input handling is compared separately, and GBK/UTF-8 signed-byte, MD5 and HMAC behavior is checked. These operations succeed without granting a host broker. All seven instrumentation tests passed on both API 24 and API 35. JVM tests also verify that revoking a ticket before a delayed response prevents Cookie storage and that deeply nested reverse-IPC input is rejected before recursive JSON parsing.

Android uses the NIO variant of core-library desugaring because the source broker uses Path/Files on API 24. URL-template regex delimiters explicitly escape closing braces for Android ICU as well as the desktop JVM engine.

## Remaining Issue #86 acceptance work

The complete compatibility tool matrix, rule entry-point integration, shared jsLib state lifetime, native allocation limits and the full engine-level reflection/network/file escape suite remain required before closing #86/#87. The real Rhino/broker IPC path does not by itself establish those guarantees. JVM child-worker tests exercise pure scripts; the reverse broker transport is Android AIDL.
