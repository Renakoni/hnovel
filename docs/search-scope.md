# Search scope contract

Search is entered through one host-owned page. It keeps the existing source search
runtime and history storage, while choosing the source at the page boundary.

## Scope

The default scope is **all enabled sources with the Search capability**. A source
chip changes the scope to one source. The aggregate scope renders one independent
block per source; a timeout, verification challenge, or empty result in one block
does not hide results from other blocks.

Each block is a preview. Tapping its header opens the existing source-owned result
page, preserving that source's pagination, book identity, login and verification
semantics. Result items use the existing source-qualified book IDs.

## Query and history

The query is shared by the selected scope. Submitting a non-blank query records it
in `UserDataPath.Search.History`; selecting a history item repeats the current
scope. The page supports deleting one item and clearing all items.

The host does not expose provider-specific search options or type selectors. A
source uses its existing default search type. This keeps the host independent of
provider-specific controls and avoids pretending that a provider supports a
cross-field search it does not implement.

## Lifetime and limits

Aggregate requests have a bounded concurrency and preview limit. Changing the
query or scope, leaving the page, replacing a source, or changing its account
generation cancels the corresponding work. A cancelled generation cannot publish
results or navigate to a stale book. The single-source page retains its existing
foreground browser and cancellation behavior.

This contract deliberately excludes fuzzy matching, ranking, provider-use
settings, and search-option migration. Those belong to separate issues.
