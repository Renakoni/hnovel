# Source execution isolation (#86, in progress)

`IsolatedExecutor` creates a fresh worker JVM for each invocation and sends only a serialized, host-issued identity, task and limits. The host locates the worker classpath from actual code sources, enforces a wall-clock deadline, watches host revocation while the worker runs, forcibly terminates late workers and rejects results after revocation. `ExecutionAuthority` issues unguessable nonces and revokes identities; forged identities are rejected.

The Android app includes a non-exported `isolatedProcess` service shell. Binder payload and result are capped at 256 KiB and the isolated UID has no app permissions. This remains a transport boundary; authenticated Binder binding, broker IPC to #85 and emulator proof are still required. JVM process isolation is not claimed as Android sandbox completeness.

Tests cover child startup outside `java.class.path`, identity forgery/revocation, timeout termination, output limits and Android compilation.
