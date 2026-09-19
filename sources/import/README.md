# Source definition import (#83)

`SourceDefinitionImporter` reads pasted UTF-8 JSON, a file or host-supplied stream,
or a URL through the existing source-network broker. UI wiring belongs to #92.
Preview never executes jsLib, login, discovery or other rules and never registers
a runtime. Even an enabled definition requires subsequent execution compatibility,
authorization and runtime integration; it is not automatically run after import.

## Preview and explicit selection

Single objects and arrays share the same strict parser. Invalid array elements and
known fields with invalid types produce issues at their original item indexes.
Unknown members (including nested rule data) remain in the saved JSON. Unknown
top-level fields are reported as unclassified; recognizing a definition does not
imply executing all its rules. Duplicate JSON property names, malformed JSON,
unsupported book types, unknown profiles and excessive nesting are explicit errors.
Limits apply to UTF-8 bytes and to every array entry, including invalid ones.

Legado's `concurrentRate` may be null or text, including an empty string, a delay
such as `"1000"`, or a request/window expression such as `"30/5000"`. Legacy integer
values are also accepted. The original value is retained; import does not install
a scheduler or override the host broker's request limits. Objects, arrays, booleans
and fractional numbers remain invalid for this field.

The format adapter recognizes Legado JSON; profiles use the identifiers from the
compatibility manifest. The extension profile is an explicit caller choice, not
inferred from the display name or advertised as a fully supported execution dialect.
Further formats supply another SourceFormatAdapter. APK/lnrp names and ZIP package
signatures are rejected, including renamed packages; no installer or script engine
is available to the importer.

Candidates show existing exact identities, possible name/key matches, duplicate
indexes, retained enable flags and notices. External customOrder is preserved in
the definition but is not applied to the app's stable source-tab order. A preview
does not select anything. A batch with bad rows can import only explicitly selected
valid rows; every selected invalid/conflicting row gets its own failure result.

Commit choices are Add, Replace and MapIdentity. Replace requires the source ID and
revision the user previewed; a stale choice cannot overwrite a newer definition.
MapIdentity is the explicit decision to retain a host ID while changing the external
key or profile. Same names never trigger replacement. Selecting two rows for one
identity rejects both, rather than allowing array order to choose the winner.

## Identity and durable state

The exact bookSourceUrl string, including fragments and non-HTTP identity keys, is
kept separately from the download URL and the host ID. A new host ID hashes framed
profile/key components; subsequent identity mapping retains that stored ID. The host
can use Identifier("rule", sourceId) when binding definitions into the registry.
Display-name changes, reimport and revision changes do not change existing host IDs.

The store persists format, profile, original key, original/final download location,
name, enable flags, canonical JSON with unknown fields, SHA-256 content digest and
local revision. Canonicalization normalizes JSON whitespace/member order only;
strings, fragments and unknown values are not rewritten. Identical reimport keeps
the revision. External lastUpdateTime is retained as definition data, not substituted
for the local revision. Origin metadata and raw definitions may contain secrets;
diagnostic toString values omit them. Nothing exports them automatically.

One versioned definitions.json snapshot is the commit point in a host-owned private
directory. Writes flush a temporary file and require atomic replacement; unsupported
atomic moves fail without falling back to a partial overwrite. A JVM lock plus file
lock serializes independent store instances/processes. There is no stale in-memory
database cache. Quota/IO failure rolls back every planned success in that snapshot;
corrupt snapshots are reported and never silently reset. Incomplete pending files
are never activated. Definitions contain no reading progress, account state or UI
selection. No migration from the abandoned prototype's file layout is supplied.

## Network boundary and validation

The trusted host supplies a dedicated, appropriately limited SourceSession for
import downloads (not a book-source login session). Downloads use ResourceKind.Import,
the broker's exact-origin/DNS/redirect permissions, bounded responses and cancellation.
The importer does not add grants, execute URL scripts or instantiate another client.
Original and final URLs are recorded separately from the source identity. Non-success
HTTP statuses, denied targets and invalid/oversized payloads never become candidates.

`source-import:test` covers row/type failures, limits, selection, conflict handling,
cross-profile identity mapping, file/stream parsing, package rejection, real disk
reopening, concurrent stores, atomic quota rollback and local MockWebServer requests.
The compatibility suite also imports its six existing synthetic sources using this
production parser/store. These tests validate the definition layer, not rule-source
execution or the future Android/UI acceptance of #86 through #92.
