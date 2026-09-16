# Native browser route fixture (#219)

This fixture exercises native navigation and redirects, iframe, fetch, XHR, a dedicated
Worker and an actual Service Worker interception. `/sw-fetch` must return the response
from `/sw-network`; merely registering a Service Worker cannot satisfy the assertion.
The pulse mode keeps making requests after document extraction to test idle ownership.

## Recorded evidence

Device: emulator API 35, `com.google.android.webview` 124.0.6367.219, Clash Meta for
Android 2.11.34; 2026-09-15. API 28 is the implementation minimum, not a claim of a
completed API 28 device run. Other providers, cellular handover, IPv6 and always-on VPN
lockdown still need device acceptance.

| Controlled scenario | Observed result |
|---|---|
| Android Clash stopped, default → bypass → default | All native child types succeeded in each mode |
| Clash running, port 18764 rejected, system proxy attached | Default failed before and after bypass; bypass passed all child assertions |
| VPN-only DNS name on port 18765 | Default child fetch succeeded; bypass could not resolve/reach that name |
| Two sources alternating modes and one shared origin | Persistent Cookie/localStorage stayed with each account; main process binding stayed null |
| Explicit route invalidation after page completion | Background Service Worker stopped; new requests on that route returned `RouteUnavailable` |
| Source session closed after page completion | Background Service Worker stopped; closed session rejected new requests |
| Cancellation followed by account retirement | Existing persistent Cookie/localStorage survived cancellation; retired account data did not enter the next account |
| Bypass kept enabled through Clash off → on → off → on → off | All native child types passed in every state; persistent data and account generation stayed unchanged |
| Wi-Fi disconnected and restored | Lost route stopped background requests and refused new ones; a new stable route resumed the same persistent account data |

An interrupted page's new localStorage write was observed to disappear on process exit.
Cancellation retention tests therefore establish state with a completed request first;
they verify preservation of existing data, not durability of an interrupted write. The
WebView API does not expose a general localStorage disk-flush acknowledgement.

The Windows host was running Clash Verge. Emulator Wi-Fi is downstream of that host;
these tests establish **Android Clash** bypass, not physical direct Internet egress or
Wenku8 latency. Host proxy settings and WebView identity were not changed. Emulator
Private DNS temporarily used `dns.alidns.com` to avoid host Fake-IP responses for the
fixture hostname; production bypass has no alternate DNS fallback.

Two additional conditions remain unverified because automatic approval review returned
`blocked by policy`: starting Android VPN with Allow Bypass disabled, and an independent
test using an unavailable global HTTP proxy. The passing Clash test had its own system
HTTP proxy enabled; it does not substitute for those two conditions.

## Local checks and CI

`NativeBrowserRouteInstrumentedTest` runs ordinary tests against an in-emulator
`http://localhost` secure context. External VPN, DNS, reconnect and Wi-Fi-loss methods
require explicit arguments and skip in normal CI. The regular class is included in
API 35 CI; `SourceVpnInstrumentedTest` checks typed refusal on API 24/35 too.

## Reproduce Android Clash routing

1. Build/install the debug app and androidTest APK. Start
   `python source-compatibility/fixtures/native-routing/server.py` on the emulator host.
   It binds only host loopback ports 18764/18765. Stop the older HTTP fixture first;
   the TLS server refuses port reuse so a stale listener cannot invalidate the test.
2. Use the owned [Clash profile](../vpn-routing/clash.yaml) on the emulator. Keep Route
   System Traffic, DNS Hijacking, Allow Bypass and System Proxy enabled. Disable Bypass
   Private Network only for this controlled local test and restore it afterwards.
3. Ensure `10.0.2.2.nip.io` resolves to `10.0.2.2` on the emulator's physical network.
   With Android Clash stopped, run:

```text
adb shell am instrument -w -r -e class indi.renakoni.nextvol.sourceexecution.NativeBrowserRouteInstrumentedTest#preparedClashRoutesAllNativeChildren -e nativeRouteUrl https://10.0.2.2.nip.io:18764/ indi.renakoni.nextvol.debug.test/androidx.test.runner.AndroidJUnitRunner
```

4. Start Android Clash and repeat with `-e nativeRouteDefault failure`. The same test
   checks default → bypass → default on one account without cache reuse.
5. Select `preparedClashCannotResolveNativeBypassChildrenThroughItsDns` with
   `-e nativeDnsUrl https://10.0.2.2.nip.io:18765/`. Only Clash maps
   `vpn-only.hnovel.test` to the owned server. The child must succeed in default mode
   and fail in bypass mode; the HTTPS root must succeed in both.

`continuousBypassSurvivesClashStopAndRestart` additionally requires
`-e nativeRouteCycle true` plus `nativeRouteUrl`. It starts/stops the prepared Android
Clash profile and finishes with it stopped. `physicalNetworkLossStopsNativeBackgroundWork`
requires `-e nativeNetworkLoss true`, active Wi-Fi and Android Clash stopped; it restores
Wi-Fi in `finally`. These flags must only be used on a prepared test device.

The fixture leaf key/certificate are public test data. The CA signing key was discarded.
Only the two exact fixture domains trust its CA in debug builds; release trust remains
system-only. There is no certificate-error override. The certificate expires in 2036;
renew the owned fixture credentials if needed instead of bypassing validation.

Restore emulator VPN options, Private DNS and accessibility settings after manual
instrumentation. Tests do not require a subscription, public site, real login or account.
