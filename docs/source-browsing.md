# Source browsing ownership

Implemented by #79 on top of the discovery contracts from #77, #78 and #91.
The primary delivery is Explore and its source-scoped search/results. Root
navigation/settings placement is delivered separately by #80, as described below.
Retirement of remaining global source adapters belongs to #81.

## Acceptance checklist

- [x] One real source per Explore tab, selected by declared Explore capability;
  built-ins first, then namespace/source ID. No Home/All/Tags inner tabs.
- [x] One vertical feed, retaining native book rows, text processing, recommendations,
  six Wenku8 list targets, and an entry to that source's categories.
- [x] Search, suggestions, more links, tag targets and book IDs have an explicit owner.
  Search-only sources are accessible from source management.
- [x] Source-local loading, retry/error, cached feed and scroll state; no global
  offline observation in Explore/search.
- [x] Requests and deferred navigation expire on departure, source change,
  registration replacement or owning account change.
- [x] Only lightweight route/selection/query/filter identities survive process
  reconstruction. Visited feeds and completed searches survive short navigation
  while their ViewModel remains alive.

## Responsibilities

| Owner | State and responsibility |
| --- | --- |
| WebSourceRegistry / SourceRuntime | Identity, declared capabilities and one registration lifetime. Resolve only the requested source; retirement cancels its work. |
| DiscoveryPageViewModel | One root navigation entry's source selection and per-source cache, scroll, draft sessions, work and browser handoff. Categories and Explore share this lifecycle. |
| ExploreHomeViewModel | Feed text processing and source-qualified more/search/categories routes. |
| SourceDiscovery / DiscoverySession | Bind remote books and targets; own one results session's filters, cursor and deduplication. |
| ExploreRepository / SourceSearch | Check Search capability before initialization; capture one runtime and qualify both search result kinds. Suggestions receive history text, never source selection. |
| ExploreSearchViewModel | One search route, selected search type, query/results, independent suggestion requests and expiring single-book navigation commands. Subscribe to history/bookshelf once. |
| Source management | Import/configuration/login and a capability-gated source search entry; it does not switch a global source. |

## Invalidation and restoration

Discovery caches are keyed by full Identifier. Their version includes source
metadata, registration generation and account generation. Publishing status
updates does not invalidate a feed; replacing even identical metadata does.
An unselected source is invalidated without loading it or clearing other tabs.
Removing the selected root source chooses the first supported source in the
stable ordering; a secondary search/results route reports unavailable and never
falls back to another source.

Failed explicit feed refresh retains the visible sections/scroll and exposes a
local typed error. Explicit retry clears that failure; switching away and back
does not repeatedly retry it. Search keeps completed results on short returns.
A cancelled partial stream is restarted for its own source on return and
deduplicated. A queued direct-book command is accepted only in its still-active
request generation; source IDs are bound before it can enter the UI queue.

SavedState contains source selection/session IDs for roots and query/type/
submitted text/expansion for search. Feeds, runtime instances and book flows are
not serialized. The existing results route carries source, target, category ID,
session ID and normalized filters.

## Main navigation and settings (#80)

The four bottom destinations are Reading, Bookshelf, Explore and Categories.
Only their exact root destinations select/show the bottom bar; search, results,
settings and source management are secondary pages. Each existing root app bar
owns its title and actions and places the shared settings action last. Settings
has one destination and a normal Back action, with no separate settings state per
root and no changes to the reader's window ownership.

`navigateToMainRoot` uses Navigation's saved back stacks for switching roots.
Reselecting a root leaves its entry intact. A default Categories visit preserves
the selected source; an explicit source shortcut sends a one-time request to the
restored category entry. The request waits for the source inventory, then goes
through the page's normal selection method. Source capability/removal handling
therefore remains in the discovery ViewModel. Only the lightweight request is
saved, never providers or feed data.

Opening Settings does not pop or replace the originating root. The shared
navigation helper accepts clicks only from a resumed entry and avoids adding a
second settings graph. Back through Settings and its source page restores the
same root entry, selection and scroll, including Activity reconstruction.

App bars apply safe top/horizontal insets. Bookshelf selection groups Pin/Remove/
Add-to-bookshelves in an overflow menu so layout, select-all, cancel and settings
remain reachable on narrow screens. These actions retain their existing callbacks.
Categories reserves bottom-bar space; Settings reserves only the system bar.

- [x] Four content roots with a shared settings action and correct selected state.
- [x] Settings/children hide the bottom bar and return to the originating entry.
- [x] Repeated clicks and source shortcuts do not duplicate root/settings entries.
- [x] Category source/scroll and nested settings navigation survive reconstruction.
- [x] Root actions remain reachable with narrow/wide layouts and system bar insets.

`MainNavigationTest` uses the real NavController, production navigation helpers
and a Compose NavHost. It checks root ownership, repeated clicks, explicit source
requests, saved scroll and Activity recreation in Settings → Sources.
`HomeSettingsActionTest` renders the actual four root app bars at 320dp and
720dp, dispatches system insets and checks touch bounds and selection actions.
Existing Categories/Explore/Bookshelf UI tests cover their content and empty state.
These are JVM/Robolectric checks; they do not claim physical-device UI coverage.

## Rule feed and native compatibility

Both provider kinds render through the host's single feed page. A provider opts
into catalogue forms/actions with hasInteractions; native feeds avoid an
unneeded category request. Forms use the same page-owned draft and browser
lifecycle as Categories. Successful form updates reload the owning feed.

A rule feed section includes categoryId as well as its current target. More
navigation passes categoryId and draft values so a new results session can
regenerate its URL after a filter change. Wenku8 tags now return tag: targets
for DiscoveryResults. No shared legacy expanded-provider filter state remains
in the host Explore UI.

Browser handoff retains only the request owned by the current destination while
its Activity covers that destination. Leaving the destination or changing source
still cancels it. Rules retain the bounded action/network/storage authority
documented in [DISCOVERY.md](../source-content/DISCOVERY.md).

See [API changes](../api/CHANGELOG.md) for route and provider compatibility.
Legacy Search/Expanded serialized routes cannot be restored by guessing a source.
The API remains an unpublished monorepo snapshot; no external plugin ABI claim is
inferred from the host tests.

## Verification

Focused JVM/Robolectric coverage uses synthetic sources and local HTTP fixtures:

- ExploreHomeViewModelTest / ExploreHomeScreenTest: capability ordering and lazy
  loading, one layer of tabs, scoped clicks, local failure/retry, late completion,
  cache/scroll, account/registration invalidation, restore/removal and interactive feed.
- ExploreSearchViewModelTest / SourceBrowseIngressTest: source-qualified results,
  shared history, one-time observers, query/suggestion cancellation, deferred
  navigation, source loss, typed errors and reconstruction.
- SourceTagPageTest / BookRepositoryOperationsTest: the book's source supplies
  the target and results, including source removal after opening another source.
- CategoriesViewModelTest / CategoriesScreenTest / DiscoveryResultsViewModelTest:
  shared lifecycle, browser handoff, independent result sessions and dynamic URLs.
- RuleDiscoveryProviderTest / Wenku8DiscoveryTest / SourcesScreenTest:
  production adapters, feed category identity, native list/tag semantics and
  imported search-only source access.

These tests do not constitute Android WebView/Binder isolation evidence or the
complete source compatibility acceptance required by #95.
