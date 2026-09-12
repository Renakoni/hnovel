# Z-Library search source

Implements #140. Z-Library is a built-in, native HTTP/JSON source. It requires
neither a plugin package nor a Python/Lua service. Source management exposes its
search entry, enable switch, API origin and exact-origin permission editor.

## Ownership and capabilities

- `ZLibrarySources` owns the durable settings and one registry generation. The
  fixed identity is `Identifier("builtin", "zlibrary")`.
- `ZLibraryClient` implements the observed eAPI search, book information and image
  requests through `SourceBroker`. It does not follow book-page slugs, download
  URLs or online-reader tokens from responses.
- `ZLibrarySource` adapts those values to the existing source-bound search and
  book contracts. It declares Search, BookInformation and Images only. No verified
  discovery catalogue is available, so it has no Explore/Categories tab.
- Search and details use normal host navigation and book IDs. Directory, reading,
  cache and export controls depend on declared capabilities or existing local
  directories. Metadata bookmarks are allowed, including on automatic-cache
  shelves; those shelves do not enqueue chapter downloads for this source.

The book's remote identity is `<numeric id>/<hash>`. Both fields come from the
server, with syntax and response-identity validation. The host qualifies it with
the fixed source ID. Changing a mirror, title or result page does not change the
stored book key; separate server IDs are retained even when titles match.

## Search protocol and bounds

`POST /eapi/book/search` uses UTF-8 form fields `message`, `page`, `limit`, optional
`languages[0]` and `extensions[0]`. The existing search-type selector provides all
languages/formats, Chinese, English, EPUB, PDF and the four language/format
combinations. No new generic filter/settings framework is introduced.

The adapter follows validated `pagination.current` / `next` values, deduplicates
only stable IDs, and rejects nonadvancing or wholly repeated intermediate pages.
Each page requests at most 20 books; one query is bounded to 25 pages / 500 books.
If a further page exists at that bound, results remain visible with a limit error.
The server's `total_items` is not treated as a complete count of its holdings.

Book information is loaded from `GET /eapi/book/{id}/{hash}` through the existing
source-owned coalescing/cache path. Cards use language, format, publication year
and file-size text as edition metadata. The eAPI does not provide novel word counts
or chapter-update timestamps; these are not invented or displayed as a 1970 update.
HTML descriptions are reduced to plain text. Missing covers use the host's existing
default cover.

HTTP 401, explicit eAPI login errors, HTTP 429 / rate-limit errors, non-JSON or
malformed responses, network failures and empty successful searches are distinct.
Search and detail screens expose retry. Only stable errors and redacted origin /
resource kind enter error UI, not response bodies, signed reader URLs or cookies.
Flow cancellation and downstream `take()` stop pagination without emitting an error.

## Mirrors, permissions and lifecycle

The bundled permission list contains the verified API origins `z-lib.gd`,
`z-lib.fo`, `library-asia.sk` and cover host `s3proxy-alp2-covers.cdn-zlib.sk`.
These are host policy, not automatic grants inferred from response URLs.
An upstream domain list is candidate information only and is not used to authorize
new sites. Future CDN changes are shown as source-owned denied-origin candidates;
the user adds them to a draft and saves explicitly, reusing #139's permission UI.

API-site edits require a complete origin without path, query or credentials. The
selected site must be approved too; removing that grant keeps the source installed
but unavailable until it is explicitly approved again. Disabling/re-enabling does
not restore revoked grants. API-site and permission drafts are saved together.

Settings persistence precedes registry publication. Mirror, permission and enabled
state changes retire the previous runtime and its requests. Other sources keep their
instances. A failed snapshot write keeps the prior settings and runtime available.
Corrupt settings start disabled and require an explicit reviewed save to recover.

The source uses its own broker namespace/profile and anonymous cookie storage.
No global account or provider selection is used. Account login, file download,
quotas and EPUB/full-text reading are outside this delivery (#58 / #68).
Network behavior is the same as other broker sources: system DNS, direct sockets,
exact origin grants and private-address/redirect protections. #146 tracks deferred
Fake-IP / HTTP-proxy transport support; this adapter does not bypass those checks.

## Live upstream evidence

Fresh requests on 2026-09-12 UTC, without an account:

| Operation | Observed result |
| --- | --- |
| `/eapi/info/ok` on the three bundled API sites | HTTP 200, `success=1` |
| `/eapi/info/languages`, `/eapi/info/extensions`, domain candidates | HTTP 200, `success=1` |
| Chinese `三体`, Chinese + EPUB, pages 1 and 2, limit 3 | Three books per page, six distinct IDs |
| English `Alice in Wonderland`, English + PDF, limit 3 | Three English PDF records |
| `16880384/478969` on `z-lib.gd` and `z-lib.fo` | Same server identity from both detail endpoints |
| Returned cover CDN URL | HTTP 200, JPEG, 26,750 bytes |

The initial controls above are Python HTTP probes. A separate Android API 35 run
of the opt-in `ZLibraryLiveInstrumentedTest` passed through the production native
adapter and source registry:

| Android path | Observed result |
| --- | --- |
| System DNS in the current Fake-IP environment | `AddressDenied`, not an origin-permission prompt |
| Fresh public-DNS control: Chinese + EPUB | 21 distinct source-bound books across two pages |
| English + PDF | Three matching records |
| Book details and second API mirror | `16880384/478969`, same source-qualified identity |
| Cover without / with explicit CDN grant | `PermissionDenied` with Image origin candidate / 26,750 bytes decoded as an Android bitmap |

Public-DNS answers are injected into test-owned brokers only. Normal app DNS and
permission policy remain unchanged; this control does not demonstrate default
Fake-IP connectivity. The test never logs reader tokens or response bodies.

Protocol references were cross-checked against live responses:

- [bipinkrish/Zlibrary-API](https://github.com/bipinkrish/Zlibrary-API/tree/6f9f14a1b6bdd92e276207486b1e0b0277d58fa6)
  for search/detail/language/format parameters (MIT).
- [ZlibraryKO/zlibrary.koplugin](https://github.com/ZlibraryKO/zlibrary.koplugin/tree/134b3c8d68f143316d81610ac3324a27ed2e82d6)
  for response, domain and authentication behavior (AGPL-3.0).
- [Unofficial eAPI documentation](https://github.com/baroxyton/zlibrary-eapi-documentation/tree/e2184c463ed6ea8e3772a8ebe0c588b421fe366a)
  as a secondary protocol reference.

No implementation from those projects is bundled. Live anonymous metadata access
does not establish login, download or full-text availability.
