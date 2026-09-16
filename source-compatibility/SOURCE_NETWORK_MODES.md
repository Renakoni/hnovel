# Per-source VPN bypass (#214, #219, #220)

## Contract

`SourceNetworkSettings` stores a device preference by stable source namespace/id. The
default is `SystemDefault`; `BypassVpn` selects an existing non-VPN Android network.
The switch may remain enabled: without an Android VPN it still uses an available physical
network, and stopping/restarting a VPN does not require toggling the preference again.

The Android adapter observes networks without requesting a cellular connection or binding
the main app process. Validated Wi-Fi/Ethernet wins, followed by available cellular. DNS and sockets
come from the same selected `Network`; bypass never uses the global Fake-IP DoH resolver.
Broker HTTP retains `NO_PROXY`, exact-origin, address, connected-peer and TLS checks in both modes.

The broker captures a route for each admitted request, including retries, redirects and
broker-forwarded browser children. Sessions own separate pools per network generation.
Network loss/property changes cancel that route's HTTP calls and retire its pools. New
requests reselect an available route. No eligible network produces `RouteUnavailable`;
connection/DNS failures preserve their observed error. Failure does not fall back to the
other mode. Cache-only reads remain available and do not prove network connectivity.

Saving a preference does not replace a runtime, rotate an account or restart aggregate
search. Updates, rollback, logout, disable/re-enable and process restart retain the mode;
removing the installed source clears it. Configuration is independent of rule JSON,
source variables and account storage.

## Integration boundaries

Rules, JS bridges, jsLib, source-owned images, background reads, Z-Library and the broker
browser share the source route. Temporary diagnostics and candidate-rule validation use
the real source's mode. Definition/collection downloads keep their own default route.
There is no separate background unmetered-only policy in the current app.

Native Chromium uses the dedicated-process route described below. Built-in Wenku8 uses
the same source preference for its Ktor content client and source-owned images (#220).
Plugin private clients do not acquire support merely by appearing in the source list (#215).

The reference project's `header.proxy` configures HTTP/SOCKS proxy connections. It is a
different transport feature and is not used to implement this Android VPN switch.

## Proxy terms and retired setting (#227)

| Mechanism | Meaning in this project |
| --- | --- |
| Old “Auto Proxy” | Scraped public proxy lists. No production source called its Jsoup retry helpers; the unused setting, startup hook and `:proxy` module are removed. |
| Explicit HTTP/SOCKS proxy | A configured proxy server carries requests. MD3 uses [`header.proxy`](https://github.com/HapeLee/legado-with-MD3/blob/fb01a76ebbbca41423e2c4c00080cc0861239fbd/app/src/main/java/io/legado/app/help/http/HttpHelper.kt#L190); broker support is deferred in [#146](https://github.com/Renakoni/hnovel/issues/146). |
| Clash Fake-IP | A DNS placeholder, commonly `198.18.0.0/15`, which Clash maps back to a hostname. It is not a public proxy address or a real website IP. The default broker resolver can look up the real address when it receives this range, then applies the usual address checks. |
| Per-source VPN bypass | DNS and sockets use an available non-VPN Android network. It remains usable without a VPN and does not fetch proxy servers. |

The retired `UserDataPath.Settings.Data.IsUseProxy` class and `is_use_proxy` key remain
for plugin compatibility. Stored values have no host effect. No replacement global
switch is introduced; source network preferences remain independent of this legacy key.

## Built-in Wenku8 route

`Wenku8Api` keeps its parser, GB18030 decoding, content headers/Cookies, business retries
and request limiter. `PluginManager` supplies the existing `SourceNetworkSettings` when
constructing the built-in source. This does not convert Wenku8 to an imported rule source
or add another network setting.

The host carries the actual `SourceRuntime.id` in `SourceRequestOwner` through content
calls, search flows, discovery, coalescing and source-owned background work. Adapters
sharing one API object therefore cannot overwrite each other's current source. The
built-in periodic reachability check has no host caller and uses Wenku8's own identity.

`Wenku8HttpClients` owns Ktor/OkHttp clients per source and route generation. Each HTTP
request resolves its route once; retries and redirects retain it. Bypass installs the
selected network's DNS **and** socket factory and explicitly disables HTTP proxies.
Default mode retains the content engine's system-proxy behavior and uses the existing
host default resolver. Route loss cancels pending calls and Ktor work and retires their
connection pools; failure never switches to the other mode. Caller/source cancellation
continues to cancel its work. A new request can use a new eligible route.

Wenku8 implements the existing `SourceImageProvider`: Coil covers, illustrations, volume
cover decoding and background downloads keep their source identity instead of falling
back to a bare URL. Images retain the existing `imageHeader` and normal OkHttp defaults;
content Cookies are not copied into image requests. Cache keys, download ownership,
source identity and account state do not change when the mode changes.

The settings page enables the switch only for the built-in Wenku8 registration. Opening
settings or saving the mode still does not construct a lazy provider or contact a site.
Other private plugin clients continue to display their existing unsupported state.

The reference [MD3 transport](https://github.com/HapeLee/legado-with-MD3/blob/fb01a76ebbbca41423e2c4c00080cc0861239fbd/app/src/main/java/io/legado/app/help/http/HttpHelper.kt#L190)
caches clients for explicit `header.proxy` addresses. This implementation reuses our
Android route adapter; it does not require users to supply an HTTP/SOCKS address.

### Wenku8 verification and reproduction

Recorded on 2026-09-16: 509 app JVM tests and 61 source-network tests passed with no
failures, errors or skips; debug and androidTest APKs built. The complete app suite and
both APKs also passed after integrating main `e9103ab6` (#228/#229).

On Android API 35, the ordinary no-VPN method passed (the opt-in method skipped when
no URL was supplied). The explicit external fixture then passed with Android Clash
stopped and with Clash Meta 2.11.34 running: default → bypass → default → bypass
produced successful documents and decoded PNGs in every no-VPN step, and failure →
success → failure → success with the port-reject VPN profile. Allow Bypass and System
Proxy were enabled; Bypass Private Network was disabled only for the local test.
No Private DNS or host proxy changes were needed for this IP-address fixture.

`Wenku8NetworkTest` exercises real Ktor/OkHttp, Coil and download workers against local
servers: two owners sharing one API, same-URL isolation, DNS/socket selection, content
encoding/headers, image bytes, redirects/retries during mode changes, route loss,
cancellation, route recovery and downloaded content after reading-cache cleanup.
The socket fixture is explicitly direct so a JVM SOCKS lookup cannot masquerade as an
HTTP proxy lookup. `MixedSourceAcceptanceTest` retains the native parser regression.

`SourcesViewModelTest` and `SourcesScreenTest` cover the enabled built-in switch, persisted
preference and the private-plugin limitation. `Wenku8NetworkInstrumentedTest` is included
in API 24/35 CI. Its ordinary test uses Android DNS/socket factories with VPN stopped,
checks true → false → true modes and reopening with bypass saved, decodes actual PNGs,
refuses a retired route and checks that the main process remains unbound.

The second method is opt-in. With the owned server below running and the Android Clash
fixture already prepared, run it once with VPN stopped and once with VPN running:

```text
adb shell am instrument -w -r -e class indi.renakoni.nextvol.defaultplugin.wenku8.Wenku8NetworkInstrumentedTest#preparedClashKeepsBuiltinDocumentsAndImagesOnTheirSelectedRoute -e wenkuRouteUrl http://10.0.2.2:18764/ -e wenkuRouteVpn false indi.renakoni.nextvol.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Change `wenkuRouteVpn` to `true` for the prepared VPN state. The test does not configure or
start a VPN. It checks both the document marker and a decoded `/image` PNG: all modes
work without Android VPN; with port 18764 rejected by Android Clash, default fails and
bypass succeeds. These are controlled transport checks, subject to the host-network
limitation below; they do not measure a real site's availability, account state or speed.

## Native browser route

Native bypass requires Android API 28+, the existing native profile capability and a
WebView provider exposing `PROXY_OVERRIDE`. Settings enable the same per-source switch
when these capabilities are present. Unsupported combinations return `RouteUnsupported`;
an absent or retired non-VPN network returns `RouteUnavailable`.

`NativeSourceBrowser` resolves the **captured route** back to the same Android `Network`
and sends its handle over the existing private Binder. Only `:source_browser_native`
binds that network, before Chromium initializes. It additionally installs a process-local
direct proxy override and waits for its completion before navigation. A proxy override
alone is not VPN bypass: the network binding controls sockets and DNS. Default mode
keeps normal Chromium system networking and proxy behavior.

The owner is the live source session plus route generation. Before changing either, the
host shuts down the old process and waits for Binder death before preparing its profile.
Route invalidation and source-session closure also stop an **idle** native process, so
Service Workers cannot keep issuing requests after their owner retires. Active request
cancellation waits for shutdown too. A lost bypass route never opens a replacement
default-route browser; a new request may select a new eligible physical network.

Changing the mode or rebuilding the process does not rotate the account or clear its
profile. Persistent Cookie/localStorage remains source/account owned. In-memory session
cookies follow Chromium's process lifetime and may be lost; this is not a promise that a
website will continue accepting the saved login. New localStorage writes still waiting
for Chromium's disk flush can also be lost when a page is cancelled and its process ends;
the public WebView API has no general localStorage flush acknowledgement. Explicit logout still retires the old
account and clears its profile; only explicitly declared general localStorage keys cross the account boundary,
as described in [the #218 retention contract](SOURCE_WEB_STORAGE.md).

Page navigation, redirects, iframe, fetch/XHR, dedicated Worker and Service Worker use
Chromium's native stack on this process route. The existing browser safety boundary is
unchanged: native subrequests are not broker HTTP peer/origin checks. No request
interception, injected networking API, fingerprint change or TLS bypass implements this.
See [native browser design](browser/README.md) and the
[controlled fixture](fixtures/native-routing/README.md) for evidence and reproduction.

## HTTP/broker verification recorded on 2026-09-15

- JVM: 55 source-network, 114 source-content and 465 app tests passed. Route tests cover
  concurrent opposite modes at one origin, captured browser/redirect chains, lost routes,
  cached bodies, address policy, durable identity and persistence failure. Existing
  Fake-IP regression tests also pass.
- Android API 35, Clash Meta for Android 2.11.34: controlled test with Clash stopped
  passes in both modes; with Clash running and port 18764 rejected, default requests fail
  while bypass requests succeed. Swapping the two sources' modes takes effect on the same
  sessions; image and broker-browser fetches use the captured route. Responses are uncached.
- The same session with bypass continuously enabled succeeds through Android VPN states
  off → on → off → on → off. Preference persistence and account generation are checked.
- A synthetic native bypass route is refused before loading a public site: unsupported
  platforms return `RouteUnsupported`, while supported platforms require a route issued
  by the actual Android network observer (`RouteUnavailable` otherwise). CI includes
  this check on API 24/35; opt-in external fixtures are skipped in ordinary CI.
- A public `www.wenku8.cc` homepage probe received HTTP redirects in both modes (302 and
  301) with caching disabled. It did not follow redirects, log in, run Wenku8 source rules
  or demonstrate a latency benefit.

### Scope of the device evidence

The emulator's host was running Clash Verge. Its non-VPN Wi-Fi is still downstream of
the host VPN/proxy, so these results prove bypass of **Android's Clash Meta only**. They
do not establish a direct physical Internet exit or a useful Wenku8 speed comparison.
An independent Android device/network is required for that acceptance check.

Host Fake-IP also affected the emulator's bound DNS. Controlled tests temporarily used
validated emulator Private DNS (`dns.alidns.com`) so the fixture hostname resolved to its
real address. This was a test-environment change; production bypass DNS has no fallback.
Clash's system HTTP proxy was enabled, private-address bypass disabled and Allow Bypass
enabled for the passing fixture. The allow-bypass-disabled scenario was not run: the
environment's automatic approval review rejected starting that supplementary test.
Always-on VPN lockdown, Wi-Fi/cellular handover and IPv6 remain device acceptance cases.

## Reproduce the controlled test

1. Run `python source-compatibility/fixtures/vpn-routing/server.py` on the emulator host.
2. Import `fixtures/vpn-routing/clash.yaml` into Clash Meta on the emulator. Enable Route
   System Traffic and Allow Bypass; disable Bypass Private Network for this local fixture.
   Select the profile and accept the Android VPN consent once. Ensure the hostname below
   resolves to 10.0.2.2 on the emulator's non-VPN network. A host VPN can invalidate that
   prerequisite. Private-address grants are confined to this instrumentation fixture.
3. Build/install the debug app and androidTest APK, then run the method with explicit args:

```text
adb shell am instrument -w -r -e class indi.renakoni.nextvol.sourceexecution.SourceVpnInstrumentedTest#clashRoutesStaySourceBound -e sourceRouteUrl http://10.0.2.2.nip.io:18764/ -e sourceRouteVpn false indi.renakoni.nextvol.debug.test/androidx.test.runner.AndroidJUnitRunner
```

With Clash running, change `sourceRouteVpn` to `true` and add
`-e sourceRouteDefault failure`. For the continuous-preference cycle, select method
`bypassPreferenceSurvivesClashStopAndRestart` and pass `-e sourceRouteCycle true` plus the
fixture URL. That method explicitly starts/stops the prepared Android Clash profile and
finishes with it stopped. Its opt-in flag prevents changing a developer's VPN in normal tests.

The separate `publicSiteRouteProbe` method accepts `sourceProbeUrl`. It only records HTTP
status/error, size, cache flag and elapsed time; a completed probe is not a successful
site-login or physical-egress assertion. Use a public page and independently establish the
network topology before drawing conclusions. Subscription credentials are not fixture data.
