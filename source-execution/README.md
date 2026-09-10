# Source execution isolation (#86, in progress)

`IsolatedExecutor` creates a fresh worker JVM for each invocation and sends only a serialized, host-issued identity, task and limits. The host locates the worker classpath from actual code sources, enforces a wall-clock deadline, watches host revocation while the worker runs, forcibly terminates late workers and rejects results after revocation. `ExecutionAuthority` issues unguessable nonces and revokes identities; forged identities are rejected.

The Android app uses a non-exported `isolatedProcess` service with an AIDL request/callback protocol. The service checks the calling UID inside Binder transactions against the installed package UID, never against `onBind`'s calling UID or an Intent extra. Callbacks are checked against the worker UID. A debug-only second isolated service verifies that forwarding the worker Binder does not grant access to another UID.

`AndroidIsolatedExecutor` requires a host `ExecutionAuthority`, binds each request to a ticket and rejects revoked or forged identities. One invocation runs at a time across executor instances; contention returns `Busy`. Requests and serialized responses are capped at 256 KiB, with the requested output limit checked again by the host. There is no worker queue. Timeout, caller cancellation, revocation and failed results retire the process. Successful calls may retain the bound worker when a library has been loaded; `close()` releases retained state when the owning runtime shuts down. The host waits for Binder death before creating a replacement; a still-live retiring Binder prevents another invocation. Source identity is not chosen by a browser tab.

`WorkerRuntime` holds at most 16 library scopes in LRU order. Keys include namespace/source/profile/revision/account generation; a new invocation ticket or book does not reset the same source library. Changed code creates a new scope, and different sources never share mutable state even with identical library text. This is an intentional source-isolation boundary beyond the reference's text-hash-only cache. The reference uses a weak LRU cache, so library state is not durable storage. Here eviction, worker failure/death or explicit close likewise reset it. A worker failure resets all of that worker's cached libraries. ScriptLibrary initialization and invocation prototype semantics are described in source-rhino/README.md. This path currently accepts inline library code; remote jsLib definition loading remains pending.

Each Binder invocation still receives a fresh result callback, reverse broker endpoint and budget. Completion retires those endpoints even when the worker remains alive. A function saved in a library cannot reuse a completed invocation's network grant: the old endpoint checks its finished flag and the original broker is closed. The service marks the executor slot available before delivering a result, avoiding a false Busy response from the immediately following call.

The Application skips Hilt and host/plugin initialization in isolated UIDs. Both service process names use Android-compatible underscores; hyphens caused an APK installation failure on API 24 despite successful compilation.

`ExecutionTask.Script` executes Rhino 1.8.1 inside the worker with JSON input/output and source globals derived from the host-issued identity. A reverse AIDL broker accepts only an operation and bounded JSON arguments. Its host endpoint authenticates the calling worker UID and the live invocation before dispatch. The worker receives no SourceSession, OkHttp client, storage path or credential manager.

`SourceExecutionBroker` binds namespace, source, profile and account generation to one existing host session, plus the invocation request budget and request context. It currently implements synchronous `java.ajax`, source configuration and cache operations. These go through the existing network/storage policies. It is owned by the invocation and cancelled on retirement; closing it does not erase the shared source session. Request dispatch and local Cookie/cache/storage commits are serialized with identity revocation, so a revoked execution cannot commit a late response. Already-dispatched HTTP requests cannot be recalled from a remote server.

## Verification

`./gradlew :source-execution:test :app:testDebugUnitTest` covers JVM protocol/host regressions. Real Android tests run with:

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=indi.dmzz_yyhyy.lightnovelreader.sourceexecution.IsolatedExecutionInstrumentedTest
```

The existing PR workflow includes API 24 and API 35 emulator jobs. They verify remote Binder transport, independent UID, rejection of a forwarded Binder from a foreign isolated UID, input-size rejection, timeout termination, forged identity rejection, revocation, cancellation and a subsequent successful source invocation. These tests execute the actual app Application and services; they are not Robolectric tests.

The integrated suite additionally runs a real Rhino Ajax request through reverse Binder to a local server, checks source storage isolation, interrupts a catastrophic regex, and cancels an Ajax call while the broker has only one request permit. The next invocation must acquire that permit and complete. The pure-tool test runs 470 expressions in the isolated worker: all 32 Base64 flag combinations across seven inputs are compared to Android's actual encoder/decoder, malformed input handling is compared separately, and GBK/UTF-8 signed-byte, MD5 and HMAC behavior is checked. These operations succeed without granting a host broker. Library tests verify cross-call mutation, fresh book bindings, source/account isolation, reset after timeout/close, and rejection of a saved old Ajax method while current Ajax still succeeds. All nine instrumentation tests passed on both API 24 and API 35. JVM tests cover library identity/LRU behavior, suppression of late Cookie commits after revocation, and rejection of deeply nested reverse-IPC input before recursive parsing.

Android uses the NIO variant of core-library desugaring because the source broker uses Path/Files on API 24. URL-template regex delimiters explicitly escape closing braces for Android ICU as well as the desktop JVM engine.

## Remaining Issue #86 acceptance work

The complete compatibility tool matrix, external library loading, rule entry-point integration, production source lifecycle notifications, native allocation limits and the full engine-level reflection/network/file escape suite remain required before closing #86/#87. Retained inline jsLib scopes and real Rhino/broker IPC do not by themselves establish those guarantees. JVM child-worker tests exercise pure scripts; reverse broker transport and retained worker lifetime are Android AIDL.
