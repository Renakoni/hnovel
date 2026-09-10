# #87 bridge capability acceptance

The merged #113 supplies the isolated executor, source/account broker, external
libraries and rule-task integration. PR #108 extends that baseline; it does not
close #86 again or claim the whole #87 profile is complete.

| Capability | Implemented behavior | Evidence |
| --- | --- | --- |
| Response errors | Validate all snapshot fields/header values inside guarded conversion; catchable redacted errors, including retained methods | ScriptResponseTest |
| Request-script options | Terminal `@js:` consumes the full suffix, including JavaScript comma expressions; delimited `<js>...</js>@result,{...}` preserves request options | ScriptRequestTemplatesTest; pinned AnalyzeUrl.initUrl/analyzeJs and AppPattern.JS_PATTERN |
| Text conversion | t2s/s2t use quick-transfer-core 0.2.16 and the pinned exclusion dictionary | ScriptTextTest; native Android fixture |
| File encodings | Worker-local pinned ICU detection; readTxtFile explicit charset wins, omitted charset uses the reference file sample | ScriptTextTest; ResourceBridgeTest |
| Fonts | queryTTF/queryBase64TTF accept byte arrays, Base64 and HTTP(S) URLs; five QueryTTF data methods and replaceFont support supplementary codepoints and filtering | ScriptFontsTest: generated triangle fonts and invalid handles; native Android fixture |
| Archives | ZIP/RAR/7z byte/string entry extraction, unzipFile/unrarFile/un7zFile/unArchiveFile and getTxtInFolder | ArchiveDecoderTest, ResourceBridgeTest; real isolated libarchive on API 24/35 |
| Metadata | Serializable book/chapter snapshots, reference field defaults, kind list and absolute chapter URL helpers, custom variables and separate nullable book/chapter writes | ScriptMetadataTest, WorkerRuleTest; native Android fixture |
| DOM | HTML/XML selection results and response.parse() have text/html/attributes/select/traversal/clone/remove methods; collections expose get/size/first/last/text/select | ScriptDomTest, ScriptRuleHelpersTest |

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

Extraction publishes one account-storage record under `/archives/<digest>`.
Quota failure cannot commit a directory prefix. Individual child paths are
logical keys within that record. Reads recheck original and final origin grants;
the execution authority guards all commits. Neither a path nor metadata can
change source/profile/account identity. getTxtInFolder joins immediate files with
newlines and deletes the directory only after successful decoding and size
checks. Nested folders fail visibly, matching the nonrecursive reference helper.

readTxtFile intentionally preserves the pinned file detector's unusual sample:
up to 8000 negative bytes with ASCII skipped. Entry-string helpers detect the
full entry bytes. Detection is heuristic; explicit charset selection is supported.
T2S exclusions preserve reference words rather than imposing additional regional
word substitutions. Dictionary initialization is synchronized by Kotlin lazy.

Metadata is an invocation snapshot, not a Room entity. Missing time values use
zero rather than inventing a host timestamp. Script metadata edits never replace
execution tickets or implicitly persist book information. Rule tasks return
`bookWrites` and `chapterWrites` separately from request `writes`; null means
delete, and the host decides when to persist them. Inherited variable maps come
from the task's explicitly scoped inputs. A standalone script retains its existing
JSON-result contract; it does not implicitly persist metadata or variables.

DOM handles serialize as markup through bounded JSON and can feed subsequent
selectors. Other JSON objects retain their original data types. No host DOM,
OkHttp, File, stream or reflection object is exposed.

## Remaining #87 acceptance

This is still a partial compatibility matrix. Binary response/stream data APIs,
the full response and DOM mutation/overload surface, and upstream book/chapter
entity methods beyond the listed snapshot/variable/URL helpers are not yet
implemented or independently verified. `book.variableMap` and upstream big-data
storage methods do not yet have full observable parity; the bounded scoped maps
and returned writes are the delivered host contract. These gaps remain #87
blockers, with feature states kept partial in coverage.json.

Cookie/login/browser/UI integrations belong to #88/#89/#91, production source
routing to #90, and activation/rollback to #94. No private source or credential
fixture was run. The generated fonts/archives are public synthetic data; their
generator is recorded alongside the files. Tests establish these concrete
contracts, not universal Legado compatibility or an absolute sandbox.
