# Source-bound host services (#85)

`SourceBroker.open` is a **trusted host operation**. It binds stable source namespace/id,
engine profile, account generation, exact-origin grants and quotas into a `SourceSession`.
IPC callers submit `BrokerRequest`/`StorageRequest`; neither DTO can choose another
source or obtain a client, path, repository or broker. #86 must bind each execution
process to the host-created session; these JVM objects alone do not authenticate IPC.

The broker is not wired to an untrusted import or script entry point in this PR.
It is independent of the #84 branch; #86/#87 compose the services later.

## URL compilation

`RequestCompiler` handles static relative/absolute HTTP(S) URLs, `{{key}}`, `{{page}}`,
`{{baseUrl}}`, `<first,second,last>` page alternatives, and JSON options for method,
body, headers/header, charset and retry. Keyword values are encoded before entering
query/form separators; JSON object bodies serialize substituted string values safely.
GB2312 and other JVM charsets are supported; the legacy `escape` option emits `%XX`/
`%uXXXX`. Existing encoded URL octets are retained. Forms encode their individual fields.
JSON request bodies are supplied as valid JSON strings/objects (a raw JavaScript expression
in a body is not static JSON). Unknown options, malformed requests, JS and browser
requirements are explicit rejected results, never silently ignored. #87 evaluates dynamic
URL/header/body expressions before this compiler; #89 handles browser requests.

## Network and credentials

- Grants authorize exact scheme/host/port combinations; multiple origins are explicit.
  All document/image/script/API/import requests use the same check. Only HTTP(S) is accepted.
- DNS policy is installed in the **actual connection resolver**. Every returned address
  must be public unless the host explicitly granted private addresses for that origin.
  Literal IPs are checked before connection because OkHttp may skip Dns for them. The
  network interceptor checks the connected peer again. Loopback/LAN/link-local, IPv4-mapped
  addresses, multicast/reserved ranges and IPv6 transition ranges are covered. A changed
  DNS answer cannot bypass an earlier preflight check. Automatic redirects, transport
  retries, global cookies, HTTP cache and system proxies are disabled.
- Every redirect goes through the same origin/address checks. Same-origin requests keep
  their headers. Cross-origin redirects drop **all caller headers**, then attach only the
  target grant's headers and domain/path/secure/expiry-matching session cookies. Explicit
  cookies override same-name jar cookies for that target; source/profile/account jars never
  share state. Host/transport/proxy routing headers cannot be supplied by a rule.
- 301/302 POST and 303 non-HEAD redirects become GET with no body; 307/308 retain method/body
  within an origin. Cross-origin forwarding of a retained body fails explicitly. The host
  can authorize a separate explicit request to that endpoint without implicitly sending
  the previous endpoint's login body.
- `retry` is the number of additional attempts (0..3 by default), for IO failures and
  429/502/503/504. Explicit retry also applies to POST; default retry is zero. Permissions,
  quotas and malformed requests are not retried. HTTP error responses remain structured
  responses so later source rules can inspect status/body.
- A total coroutine deadline covers queueing, rate delays, redirects, retries and body
  reads. Session permits and minimum start intervals apply to all attempts/hops. Cancelling
  the caller cancels its OkHttp call through body consumption and releases permits;
  closing/replacing the session cancels in-flight/queued requests and rejects old handles.
- Response bytes are bounded, with a header bound as well. Response charset comes from an
  explicit request override, then Content-Type, then UTF-8. Request encoding and response
  decoding are distinct. The protocol returns bytes for images/scripts and an explicit
  text decoder for callers.

## State and storage ownership

Persistent config keys use source+profile; account KV/cookies also include account generation.
Opening a new account generation retires the old session, preserves config and starts separate
account state. A source/profile can only have one active session in a broker. Host grant changes
require closing the existing session first. No current-source global or UI state is read.

Persistent cookies retain absolute expiry; session cookies remain in memory. Cookie persistence
has its own private namespace, inaccessible through account KV. Cookie persistence failures are
reported instead of silently switching to an unauthenticated jar. Full login/logout/browser
cookie coordination belongs to #88/#89.

KV accepts opaque keys, never paths. Namespaces and filenames are hashes of unambiguous stable
components; writes enforce bytes/entry quotas before atomic replacement. Path checks reject
symlinks/aliases outside the controlled directory. Null writes delete a key. Stored values are
not encrypted by this module; the caller supplies app-private storage, and IPC/process access
restrictions are #86's responsibility.

`StorageArea.Cache` provides bounded session-local get/put/delete and per-entry TTL.
`RequestVariables` is a separate bounded frame, copied per invocation, with snapshot reads.
Neither is durable source configuration. HTTP GET caching is opt-in, keyed by URL/response
charset/effective headers (including scoped credentials); it is a host cache with a fixed TTL,
not a claim to implement all HTTP cache directives. Cookie changes invalidate it. Cache-only
misses fail without DNS/network, response arrays are copied, and retirement clears caches.

Serializable DTOs define the later IPC payloads. Their diagnostic strings omit headers,
bodies, keys and URLs; failure results contain stage/code/attempt, not exception messages
with paths or secrets. Payloads necessarily carry data for the caller but are never logged
by this module. No JS engine, login UI, browser or Android process sandbox is implemented here.

## Verification

Run `gradlew.bat :source-network:test :source-compatibility:test`.
MockWebServer fixtures explicitly authorize only their local test origins. Tests never access
external sites: encoding, actual wire headers/body, response decode, origin redirects,
cookie priority/persistence, same-domain source/profile/account isolation, cache-only behavior,
TTL/quotas, path containment, DNS rebinding/IPv6/literals, concurrency, rate, cancellation,
timeout, retry and serializable/redacted result contracts are covered.

The existing compatibility sources also run their static search URL through the production
compiler. Their full URL/HTTP/storage profile entries remain **partial** until process binding
and script entry points land; broker tests are not falsely labelled a full upstream JS oracle.
