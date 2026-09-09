# Rhino source runtime (#87)

Provides a fresh Rhino scope per invocation, source/profile/book/chapter/result context, and an allow-listed `HostBridge` callback. ClassShutter denies Java class access; bridge failures, syntax failures and runtime failures are returned as structured results without exception text. Script and result budgets are bounded. Mutable variables belong to the supplied frame and are never global across source sessions.

Network, storage, cookies and process lifecycle remain host capabilities owned by #85/#86. This module does not load arbitrary plugins or expose Android objects, Activity, NavController, Repository or WorkManager. Browser and login bridges remain #88/#89.
