# Rule-source discovery and actions (#91)

`RuleDiscoverySession` adapts imported novel definitions to the host discovery contract.
It uses the existing `RuleSource` / worker wire / source broker path, not a second script
engine. `RuleDiscoveryProvider` binds this data to the source tabs and result pages from
[#78](https://github.com/Renakoni/hnovel/issues/78). Explore-root integration and bottom
navigation remain [#79](https://github.com/Renakoni/hnovel/issues/79) and
[#80](https://github.com/Renakoni/hnovel/issues/80).

## Provenance and compatibility scope

- Standard reference: [hectorqin/legado@da17bb2](https://github.com/hectorqin/legado/tree/da17bb2bed44f30b12a524c2457e32a20b16fa41),
  particularly `BookSourceExtensions.kt` and the `ExploreKind` / `ruleExplore` fields.
  Static `title::url` entries, JSON entry arrays, and `@js:` / `<js>` catalogue evaluation
  feed the existing book-list pipeline. Newline and `&&` separate static entries.
- Extension evidence: [Luoyacheng/legado@8b87c5a](https://github.com/Luoyacheng/legado/tree/8b87c5aba4df91c39a3a0939a68a1180b9f2ee1c),
  `ExploreKind.kt`, `InfoMap.kt`, `ExploreAdapter.kt`, `SourceLoginJsExtensions.kt`,
  `RssJsExtensions.kt`, `SourceCallBack.kt`, `JsExtensions.kt`, and `ruleHelp.md`.
  This is a separately identified extension, not the pinned standard profile.
- The private-sample inventory in `source-compatibility` is historical member/shape
  evidence only. No private definition, endpoint, credential, or plugin package is used
  by the tests or included here. Passing these fixtures does not prove that a live
  private source or every overload of an extension host works.
- `exploreScreen` exists in the standard declaration, but no executable upstream
  contract was established. Here it explicitly accepts the same finite control rows
  described below, without URL rows. Likewise, `upConfig` is a documented source-local
  host protocol; an exact upstream overload was not found. Neither is advertised as
  upstream differential compatibility.

## Data, identity, and ownership

There is one tab per registered source. URL entries become ordered categories and feed
sections, never nested Home/All/category tabs. A blank URL is a heading. Loading a
catalogue does not fetch every result list: a feed fetches at most the first actionable
section's first-page preview (six books); later sections retain their own More target.

An entry may supply an explicit `id`. Otherwise URL-row IDs are derived from the field,
type, URL, action, and duplicate occurrence, excluding display titles. Input keys remain
their original `title`; `viewName` changes their display label after defaults have been
installed. If filtering regenerates a literal URL, an explicit stable category ID lets
the result page resolve the new target; URL templates can instead read `infoMap` and
`page` at request time. Removed categories fail explicitly instead of choosing another.

| State | Owner and invalidation |
| --- | --- |
| Source identity and book IDs | Registry namespace/source ID; titles never select a runtime |
| Script/network/storage authority | Existing source/profile/revision/account ticket and broker |
| Catalogue, drafts, controls, actions | A host page session over one captured runtime |
| Result filters, cursor, accumulated books | One navigation entry, independent of other result pages |
| Explicitly saved `infoMap` and source configuration | Source/profile settings, shared only by explicit persistence |
| Login state, Cookie, browser state | Existing account/session services and origin grants |

Changing source, stopping the page, replacing a revision, or rotating an account cancels
owned work and invalidates old command epochs. The runtime checks its authority before
accepting results. Account rebind uses atomic registry replacement so login does not
temporarily remove the source tab. Other sources retain loaded content and scrolling.
Result-page transient drafts survive filter refreshes but are discarded on runtime or
account replacement; only normalized visible filter values are saved in SavedState.

Rhino `jsLib` keeps its established lexical/shared scope. Helpers can receive the current
`infoMap` and `java` as explicit arguments; current bindings are not injected into old
closures. Retained discovery-native methods reject a later invocation. All values and
deferred actions cross the worker wire as bounded JSON, never Compose, Activity, or
NavController objects.

## Finite rows and action protocol

URL rows and the `text`, `toggle`, `select`, and `button` extension types are recognized.
Choice values come from `chars`; toggle needs two choices, select at least one. Defaults
must be allowed choices. Text is explicitly applied by the user. Control changes update
the page's string map before running their action. Buttons receive `isLongClick`.
Presentation `style` is not interpreted as a layout program: the host owns the linear
layout. Unknown row types/fields and invalid choices produce field-qualified errors.

| Script API / field | Host behavior |
| --- | --- |
| `infoMap.get()`, `get(key)`, `put(key,value)`, bracket access | Current page's string-map draft |
| `infoMap.set(map)` | Replace the draft, not merge it |
| `infoMap.save(seconds=0, need=true)` | Opt in to source-owned persistence; zero has no expiry, positive values have a TTL; `need=false` cancels further saving |
| `java.upLoginData(map)` | Merge string fields into the current source form; null is a valid no-op |
| `java.refreshExplore()`, `source.refreshExplore()`, `java.reLoginView([boolean])` | Coalesced refresh of the originating page; delta/full redraw flags have the same host result |
| `java.upConfig()` | Open the existing settings detail for this source |
| `java.upConfig(map)` | Replace this source's Config `variable` JSON and refresh; never edit global reader preferences |
| `java.open('login', ..., ..., sourceKey)` | Open this source's login UI through the existing login service |
| `java.open('explore', url, title, sourceKey)` | Open a new source-qualified result session with a draft snapshot |
| `java.open('search', scope, keyword, sourceKey)` | Search only the originating source; the two-argument form uses the second argument as keyword |
| `java.searchBook(keyword[, scope])` | Source-local search; scope must be empty or this source's key; the actual current `source` / `java.getSource()` object overload is also accepted |
| `java.showBrowser(url[, html, script, configJson])`, HTTP(S) button actions | Existing foreground browser port with source headers, origin grants, cancellation, and a title-only configuration object |
| `java.removeCookie(url)` | Existing source-bound Cookie broker port, not global browser state |
| `java.getThemeMode()`, `getThemeConfig[Map]()`, `getReadBookConfig[Map]()` | Data snapshots of the host appearance; changing returned maps cannot edit host settings |

The host supplies effective light/dark mode (`1`/`2`), theme primary/background/text
colors, and reader font size/line height/weight. This is not the complete Android
reference configuration object or its local paths. Result actions cannot select another
source or an arbitrary Android destination. Unknown actions/config keys are rejected.

`customButton` and `eventListener` are booleans, not executable strings. Enabling the
source button requires the event flag and `ruleContent.callBackJs`. Click and long-click
run that callback with `event = clickCustomButton` / `longClickCustomButton`, `result`
empty, and `book`/`chapter` null. The button is exposed in the source's catalogue controls.
Reader, shelf, and detail lifecycle callback integration is not part of this
discovery/settings adapter; no arbitrary event bus or custom page DSL is installed.

Persistence is deliberately committed only after successful parsing/action validation,
not from an Android on-pause callback as in the extension reference. Failed actions do
not publish their drafts or queued configuration writes. Unsaved edits never seed another
page; explicitly persisted settings may seed later pages, including after an account
change. A previously opened page keeps its own draft. Live network/storage methods still
have the existing broker semantics; this is not a transaction that rolls back arbitrary
script-issued HTTP or Cookie operations.

## Bounds, errors, and verification

- At most 128 combined rows, 64 choices per control, 16 deferred actions per interaction,
  128 draft fields, 4,096 characters per value, and 32,768 total draft characters.
- Existing Rhino instruction, script, bridge, rule-call, IPC, and execution deadlines
  still apply. No new network/browser authority is granted by a catalogue or action.
- Result requests accept pages 1–64. The last allowed page is delivered; a further load
  reports `ruleExplore.page` before issuing HTTP. Nonempty duplicate-only pages terminate
  pagination. A failed request retains its cursor and previous books for retry.
- Missing discovery, authentication/browser requirements, permission denial, network
  failure, and invalid rules remain distinct host errors. Parse/action errors retain
  locations such as `exploreUrl[0].type` or `discovery.actions[0].open` in the UI and
  diagnostics. A search-only source is not removed from search or direct-link reading.
- `RuleDiscoveryTest` uses actual imported definitions, the production worker wire and
  broker, and local HTTP fixtures. `ScriptDiscoveryTest` tests invocation ownership and
  limits. `RuleDiscoveryProviderTest` exercises real adapter identity, previews, retry,
  diagnostics, and atomic account rebind. Import tests verify flag types. Categories
  Compose/ViewModel and result ViewModel tests verify navigation intent data, control
  callbacks, refresh counts, cancellation, scrolling, and independent result filters.
- The browser-port fixture records the existing port and forwards to local HTTP; it is
  not an actual WebView. Robolectric and JVM wire tests are not real Android Binder/
  isolated-process evidence. No new emulator run or remote CI verification is claimed.
