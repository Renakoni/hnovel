# Source execution isolation (#86)

This module defines the host/worker execution boundary. `IsolatedExecutor` creates a fresh JVM worker for each invocation, sends only a serialized host-issued `ExecutionIdentity`, task and `ExecutionLimits`, and forcibly terminates a worker that exceeds its deadline. Output is bounded inside the worker and the host returns structured failure codes. A worker has no repository, network client, filesystem root or navigation reference in its protocol.

The identity contains source, profile, revision and a host nonce. It is data for later authenticated IPC binding; this JVM module does not claim to authenticate an Android Binder caller or provide a kernel sandbox. Android isolatedProcess wiring and emulator verification remain required integration work. JavaScript/Rhino and broker capabilities are added by #87 and must be bound through this identity.

Tests cover successful round trips, output limits and forcible termination of a sleeping worker.
