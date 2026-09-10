# #87 bridge capability acceptance

The merged #113 supplies the isolated executor, source/account broker, external
libraries and rule-task integration. PR #108 completes the #87 worker bridge
profile on that baseline. Its closure does not close the later login, browser,
reader UI or production routing issues. The contract is bounded script data,
not unrestricted access to the reference application's Java/Android objects.

| Capability | Implemented behavior | Evidence |
| --- | --- | --- |
| Response errors | Validate all snapshot fields/header values inside guarded conversion; catchable redacted errors, including retained methods | ScriptResponseTest |
| Request-script options | Terminal `@js:` consumes the full suffix, including JavaScript comma expressions; delimited `<js>...</js>@result,{...}` preserves request options | ScriptRequestTemplatesTest; pinned AnalyzeUrl.initUrl/analyzeJs and AppPattern.JS_PATTERN |
| Text conversion | t2s/s2t use quick-transfer-core 0.2.16 and the pinned exclusion dictionary | ScriptTextTest; native Android fixture |
| File encodings | Worker-local pinned ICU detection; readTxtFile explicit charset wins, omitted charset uses the reference file sample | ScriptTextTest; ResourceBridgeTest |
| Fonts | queryTTF/queryBase64TTF accept byte arrays, Base64 and HTTP(S) URLs; five QueryTTF data methods and replaceFont support supplementary codepoints and filtering | ScriptFontsTest: generated triangle fonts and invalid handles; native Android fixture |
| Archives | ZIP/RAR/7z byte/string entry extraction, unzipFile/unrarFile/un7zFile/unArchiveFile and getTxtInFolder | ArchiveDecoderTest, ResourceBridgeTest, ScriptResourceConsumptionTest; real isolated libarchive on API 24/35 |
| Metadata | Field getters/setters, lazy variableMap, 10,000-character small/big store split, explicit nullable writes, metadata changes across script stages and wire, author/display/kind/filename/URL helpers and detached conversion snapshots | MetadataContractTest, ScriptMetadataTest, MetadataWireTest, WorkerRuleTest; real Binder fixture |
| Responses | Original signed bytes, in-memory streams, all Connection.Response method names and data overloads, mutable headers/cookies, parse/buffer/consumption state, declared vs detected charset, consumed StrResponse raw body and response metadata | ResponseContractTest compares pinned Jsoup with MockWebServer; ScriptResponseTest; real Binder fixture |
| DOM | Pinned Node/Element/Document/Elements data and mutation methods, scalar/collection/DOM overloads, callbacks, attributes/dataset, parser/output settings, forms/key-values, tracked ranges and node kinds | DomContractTest compares Jsoup mutations, callbacks and form data; ScriptDomTest, ScriptRuleHelpersTest; real Binder fixture |
| URL/chapter tools | JsURL properties/getters and query-map semantics, chapter-number normalization including full-width digits and the reference's first-match return | ScriptUrlsTest; pinned JsURL, JsExtensions and StringUtils |

## Ownership and limits

Decoders, conversion dictionaries, font parsers and DOM trees live in the worker.
Android uses pinned libarchive 1.1.6 via direct memory buffers; it never opens
archive-provided paths. The JVM harness has a ZIP decoder and explicitly fails
non-ZIP input; the Android implementation supplies RAR/7z support. Native work
retains #86's hard process deadline and allocation monitor. The interpreter's
instruction counter is not a native-code timeout.

Font objects expose only five named methods. Their parser state is attached
privately to native JS objects, never placed in a script-readable field. Font
parsers cache at most four inputs per invocation; retained library facades can
keep parsed data, bounded by the worker lifetime. This deliberately replaces
the upstream global font cache. A method saved in a library still uses the
current bridge budget; loading a URL retains the original broker authority.
The pinned queryTTF implementation accepts HTTP(S), Base64 and byte arrays;
its comment mentions local paths but the actual string branch does not read a
file. Scripts can pass `java.readFile(path)` to queryTTF.

Every bridge has its existing JSON size/depth limit (64 KiB by default in Rhino,
256 KiB IPC). Encoded bytes and JSON punctuation count toward the limit; these
are not promises to accept arbitrarily large fonts or archives. Extraction is
limited to 256 entries and the current bridge byte budget across all contents.
Absolute paths, traversal, backslashes, drive prefixes, duplicates and oversized
content fail. Android also rejects links, special entries and encrypted entries.
The portable ZIP decoder treats entries as data, with no filesystem extraction.

Extraction publishes one account-storage record under `/archives/<unique-token>`.
Each extraction gets its own opaque token, even for the same resource URL;
consuming one cannot delete another extraction published between its read and delete.
Quota failure cannot commit a directory prefix. Individual child paths are
logical keys within that record. Reads recheck original and final origin grants;
the execution authority guards all commits. Neither a path nor metadata can
change source/profile/account identity. getTxtInFolder joins immediate files with
newlines and deletes the directory only after successful decoding and size
checks using the actual bridge serializer, including Unicode escaping. Consumption
is not rolled back if later script execution or final result serialization fails.
Nested folders fail visibly, matching the nonrecursive reference helper.

readTxtFile intentionally preserves the pinned file detector's unusual sample:
up to 8000 negative bytes with ASCII skipped. Entry-string helpers detect the
full entry bytes. Detection is heuristic; explicit charset selection is supported.
T2S exclusions preserve reference words rather than imposing additional regional
word substitutions. Dictionary initialization is synchronized by Kotlin lazy.

Metadata is an invocation snapshot, not a Room entity. Missing time values use
zero rather than inventing a host timestamp. Script metadata edits never replace
execution tickets or implicitly persist book information. Rule tasks return
`bookWrites`/`chapterWrites` and `bookBigWrites`/`chapterBigWrites` separately
from request `writes`; null means delete. Values below 10,000 characters go to
the small map, longer values to the big store, and putVariable removes the other
representation. Direct variableMap methods change the small map without eagerly
reserializing `variable`, as in the reference. The map initializes lazily from
that field; explicitly supplied scoped variables take precedence. Successful
rule tasks also return changed `book`/`chapter` JSON snapshots (null means no
metadata change). The host decides when to persist these outputs; source and
book identity still come from its execution ticket. A standalone script retains its existing
JSON-result contract; it does not implicitly persist metadata or variables.
toSearchBook/toBook copies inherit big values into independent maps and own their
write sets. Changing a converted object cannot emit persistence writes for the
original book. This detached snapshot contract intentionally differs from the
reference application's URL-keyed shared big-variable storage.

DOM handles serialize as markup through bounded JSON and can feed subsequent
selectors. Other JSON objects retain their original data types. Worker-owned
Jsoup/OkHttp/stream values stay behind native JS facades; no Java wrapper,
host client, descriptor, socket or reflection object is exposed.
If a native data method exceeds its size budget after entering the operation,
its owning library drops the complete scope/realm, including retained aliases;
the next invocation reinitializes the library scripts. Individual native mutations
are not rolled back. Pre-argument validation failures and pure crypto budget
failures preserve the existing valid library state.

## Data interface semantics and explicit exclusions

Production response snapshots carry Base64 original bytes, final method/URL,
protocol/timestamps and nullable declared charset. Missing declared charset stays
null so parse() can detect HTML meta/BOM encoding. Legacy text-only mock snapshots
use UTF-8 text bytes; they cannot recover binary data that was never supplied.
Buffered bodies support repeated body()/bodyAsBytes()/parse() calls.
bodyStream() is only available before consumption, including before bufferUp(),
matching pinned Jsoup 1.16.2. After an unbuffered parse(), bufferUp() is a no-op
and cannot restore bytes or allow another parse/stream. ResponseContractTest
compares all 125 three-operation sequences over these five methods against a
real nonempty Jsoup response. Streams expose read overloads,
skip, available/ready, mark/reset and close over bounded memory. StrResponse's
raw body is already closed, matching the reference's text() consumption.
Mutating response headers/cookies/URL changes only that response view, never
the broker's session or permissions. Session Cookie integration is #88.

`ScriptDom` is the closed class/method dispatch inventory. It covers the pinned
Jsoup data methods and supported scalar/DOM/collection/Reader overloads. Java
Class/Evaluator/Appendable overloads, constructors, connection factories,
FormElement.submit and Document.connection are outside this data profile: they
require arbitrary Java objects or unbrokered network authority. Callbacks run
under the current instruction budget; retained methods use current size limits.
Map mutations use put/putAll/remove/clear/entry.setValue; map field reads are live
conveniences. Collection get/size/add/set/remove/clear operate on the captured
list, with native array snapshots for JavaScript indexing and Array helpers.
These are explicit collection facades, not Java collection wrappers.

Book helpers implement data getters/setters, real author, display cover/intro,
kind list, unread/last chapter count, charset, folder name and detached
toSearchBook/toBook snapshots. Chapter helpers include primaryStr, display title
and supplied replacement rules, file/font names and absolute URL fallback.
`chineseConverter` is an explicit task value (0 unchanged, 1 simplified, 2
traditional); no settings framework is introduced. Reader-global preferences,
simulated reading schedules, localized SearchBook UI, Room save/delete,
book migration and Parcelable/component/copy machinery are not script-data
entities. Reader/persistence integration belongs to #90/#91. Invalid APIs or
overloads fail visibly rather than returning a fake success.

The raw response is a bounded metadata projection. It does not reconstruct
OkHttp's connection/TLS/redirect/cache history, request credentials/body or
trailers. Those transport internals are not a second network capability.
Font format 12 support is a deliberate extension over the pinned QueryTTF,
which ignores that format. Directory/table/glyph bounds are checked before
table-derived array allocation; format 12 expansion is capped at 65,536 mappings.
The supplementary fixture maps U+100000 to the same synthetic triangle as A.
Format 4 preserves zero glyph-array entries as missing and applies idDelta
modulo 65,536 only to nonzero entries. Odd offsets, invalid segment counts,
inverted ranges and glyph-array overreads are rejected. This corrects the pinned
parser's arithmetic; tests use hand-encoded cmap tables and queryTTF/replaceFont.

Cookie/login/browser/UI integrations belong to #88/#89/#91, production source
routing to #90, and activation/rollback to #94. No private source or credential
fixture was run. The generated fonts/archives are public synthetic data; their
generator is recorded alongside the files. Tests establish these concrete
contracts, not universal Legado compatibility or an absolute sandbox.

Publication validation: 95 Rhino, 41 execution, 10 rules, 20 network and 43
compatibility JVM tests pass (209 total). Debug and AndroidTest APKs build;
the real isolated-service suite passes 21 tests on each of API 24 and API 35,
including retained-library recovery after an oversized DOM mutation.
