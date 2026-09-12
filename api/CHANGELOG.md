# Plugin API changes

## Unreleased — retire global source selection (#81)

- Remove `WebBookDataSourceManagerApi.getWebDataSource()`,
  `UserDataPath.Settings.Data.WebDataSourceId` and `Route.Main.Settings.SourceChange`.
  The manager registers/unregisters sources; the host resolves requests using the
  source identity in a book, image, discovery session or saved work input.
- Remove the host's mutable provider and synchronous runtime facade. Native
  volume-cover and tag callbacks remain bound to the owning runtime. A bare
  debug book ID has the fixed Wenku8 meaning; imported-source clients supply
  source-qualified keys and cannot use a selected-source fallback.
- These removals break old source/API consumers and serialized source-change
  routes. They do not provide old APK source compatibility, migrate old data or
  publish/change the API artifact version. Non-source plugin APIs are unchanged.

## Unreleased — source-scoped Explore (#79)

- `Route.Main.Explore.Search` now requires a namespace and source ID;
  `Categories` accepts an optional initial source. The source selection is local to
  that navigation entry. Shared search history never supplies a source.
- The old `Explore.Expanded` route is replaced by `DiscoveryResults`. A tag's
  `bookTagPage` value is a discovery target, not a legacy expanded-provider key.
  Plugins navigating with the old route must migrate and recompile.
- `DiscoverySection.categoryId` lets a result session regenerate a dynamic target
  using its own filter values. `DiscoveryProvider.hasInteractions` tells the host
  to include the provider's catalogue forms and actions in its feed.
- These route/schema and constructor changes are not a source or binary
  compatibility guarantee. Old serialized search/expanded routes cannot be
  restored with a guessed source. This remains the monorepo's `0.4-SNAPSHOT` API;
  no artifact is published or compatibility version advanced by this change.

## Unreleased — rule-source discovery (#91 / PR #126)

This change is developed against the monorepo's `0.4-SNAPSHOT` artifact and
`ApiMetadata.API_VERSION = 4`. It does not publish a new artifact or change the
plugin compatibility groups.

- `DiscoveryFilter.Text` extends the sealed filter contract. Recompiled Kotlin
  consumers must handle it in exhaustive `when` expressions. Previously compiled
  consumers that receive the new variant can fail in an exhaustive dispatch; adding
  the variant is not a blanket source/binary compatibility guarantee.
- `DiscoveryProvider` adds `openSession`, `catalog`, `interact`, and `openBrowser`,
  plus the default `failureField` getter. Defaults preserve the in-tree stateless
  provider behavior, but separately compiled implementations depend on the Kotlin
  JVM-default/`DefaultImpls` mode and Android desugaring. Default source bodies alone
  do not establish binary compatibility with every existing plugin artifact.
- `DiscoveryCatalog`, `DiscoveryUpdate`, `DiscoveryAction`, `DiscoveryEnvironment`,
  and `DiscoveryButton` describe page-local forms and deferred host actions. The
  source runtime retains identity, revision/account, and network/storage authority;
  providers must not retain Android UI objects in those values. Stateful providers
  should return a new provider from `openSession` rather than share page drafts.

Before publishing an API release, record these changes in its versioned release
notes, verify representative independently compiled plugins and exhaustive filter
consumers, and select the artifact/API compatibility version according to those
results. Do not republish this as an undocumented compatible replacement or infer
external plugin ABI support from the monorepo's passing tests. No plugin binary
compatibility verification or release is claimed by this PR.

See [the discovery protocol](../source-content/DISCOVERY.md) for input-key identity,
catalogue persistence, source-local search, and browser lifecycle semantics.
