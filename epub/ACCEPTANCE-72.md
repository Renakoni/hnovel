# #72 EPUB XHTML compliance

Implementation starts at `bd49433d194a51906315c9548e0911aca288f335` in
`fix/p72-epub-xhtml`. This document covers the export-format change and the
separate investigation of #71 / EXPORT-001. The shared issue-research snapshot
is an input, not a validation result for this branch.

## Design and observable behavior

- Built-in text and image components create XHTML QNames before joining the
  document. `SimpleContentBuilder.addContent` qualifies unnamespaced extension
  HTML while leaving foreign namespace subtrees intact. It does not remove
  `xmlns` strings or turn SVG/MathML into HTML. Unsupported extension markup
  is still subject to EPUB validation; this is not a general HTML sanitizer.
- Worker chapter heads use `ChapterInformation.title`, including empty-body
  chapters. The chapter DSL supplies the chapter title when its content builder
  has no nonblank title. An explicitly provided nonblank content title is
  preserved; setting a title again updates the existing element. Missing/blank
  book and chapter names use `Untitled book` and `Untitled chapter`. If both the
  package identifier and original book name are missing/blank, a UUID URN is
  generated, so unnamed books do not all acquire the placeholder as an ID.
  No title is extracted from body text and no author is fabricated as Anonymous.
- XML 1.0 filtering uses Unicode code points: TAB/LF/CR, U+0020–D7FF,
  U+E000–FFFD and U+10000–10FFFF survive. Invalid controls, isolated surrogates
  and invalid/overflowing numeric references are removed. DOM escaping still
  handles ampersands and angle brackets. Text supplied through the text API is
  literal text, so the characters `&#0;` are not treated as a request to insert
  a control character. Numeric-reference filtering operates on serialized XML.
- Serialization no longer inserts indentation/newlines into mixed content.
  Leading/trailing spaces, tabs, emoji and supplementary CJK characters survive
  decoding. Text-component newlines keep their existing `br` representation,
  including the existing trailing break. Tests check both text and break counts.
- No cover manifest item is emitted until a cover is registered. Blank optional
  creator/description/publisher values are omitted, avoiding empty Dublin Core
  elements. Missing optional information is a normal export input.
- Inline SVG/MathML chapters declare the corresponding manifest properties.
  The image helpers emit an empty `alt` attribute because the component protocol
  has no image-description field; this does not establish accessible image
  descriptions or accessibility conformance.
- Worker image references use `image_` plus the full SHA-256 of the source URI
  for valid XML IDs and resource paths. Two different URIs with the same Java
  string hash no longer overwrite each other. Repeated references share a ZIP
  resource, but this change does not deduplicate download tasks. The existing
  source/book/task-scoped temporary directory remains the isolation boundary.
- Worker images continue through the existing JPEG transcoder, including PNG
  sources; the manifest describes the resulting JPEG bytes. The library's
  `image`/`imgRes`/`cover` APIs retain their JPEG input contract. Direct PNG
  resources use the existing `res(..., mediaType = "image/png")` API.

JSON component IDs, serialization, extension-field handling, invalid-entry
skipping and serializer exception propagation are unchanged. No reader layout,
source runtime, settings, schema, Gradle configuration or SAF protocol changes
are part of this implementation.

## Changed files

| File | Responsibility |
| --- | --- |
| `api/src/main/kotlin/io/nightfish/lightnovelreader/api/content/component/SimpleTextComponentData.kt` | Qualified text/break elements; literal text reaches the central XML cleanup |
| `api/src/main/kotlin/io/nightfish/lightnovelreader/api/content/component/ImageComponentData.kt` | Qualified image elements and `alt` |
| `app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/work/ExportBookToEPUBWork.kt` | Real chapter-title/content wiring and image resource IDs |
| `epub/src/main/kotlin/io/nightfish/potatoepub/builder/ChapterBuilder.kt` | Effective chapter/title fallback |
| `epub/src/main/kotlin/io/nightfish/potatoepub/builder/SimpleContentBuilder.kt` | Extension HTML attachment and title/text/image output |
| `epub/src/main/kotlin/io/nightfish/potatoepub/builder/EpubBuilder.kt` | Manifest, optional metadata and missing book metadata |
| `epub/src/main/kotlin/io/nightfish/potatoepub/xml/XmlFormat.kt` | XML legal characters and content-preserving serialization |
| `epub/src/test/kotlin/XmlComplianceTest.kt` | Character/namespace regressions |
| `epub/src/test/kotlin/EpubComplianceTest.kt` | Actual library EPUB fixtures |
| `app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/work/EpubXhtmlWorkerTest.kt` | Actual host/Worker fixtures |
| `epub/verify_epub.py` | Fixed-version conformance verification of generated fixtures |
| `epub/ACCEPTANCE-72.md` | This design, evidence and follow-up record |

## Verification result (2026-09-10)

The final worktree build passed **24 tests**: 6 EPUB JVM tests and 18 selected
App/Robolectric tests, with no failures, errors or skips. **All 12 generated
EPUBs passed EPUBCheck 5.3.0 with zero fatal errors, errors or warnings**.
`git diff --check` also passed. The generated report index is
`build/p72-verification/epubcheck/results.json`; the test totals are recorded in
`build/p72-verification/test-results.json` and Gradle's JUnit XML reports.

## Reproduce verification

From the task checkout, with the existing JDK/SDK and Gradle dependencies:

```powershell
& 'E:/Java/jdk-22/bin/java.exe' -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :epub:test --offline --console=plain --max-workers=2
& 'E:/Java/jdk-22/bin/java.exe' -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:testDebugUnitTest --tests '*EpubXhtmlWorkerTest' --tests '*SourceExportWorkerTest' --tests '*ContentDecodingContractTest' --offline --console=plain --max-workers=2
python -X utf8 epub/verify_epub.py --java 'E:/Java/jdk-22/bin/java.exe' --epubcheck 'E:/H-novel/ref/hnovel-issue-research-2026-09-10/epubcheck-dist/epubcheck-5.3.0/epubcheck.jar'
git diff --check
```

The verifier requires exactly EPUBCheck 5.3.0 and reads fixtures generated under
this checkout's `epub/build` and `app/build`. It writes JSON diagnostics, logs,
file hashes and a result index to `build/p72-verification/epubcheck`. It treats
warnings as a failed acceptance check. It does not invoke the old research probe
or read another checkout's compiled writer.

Coverage:

| Test group | Assertions |
| --- | --- |
| `XmlComplianceTest` | XML code-point boundaries, surrogate handling, numeric-reference overflow, literal entity-like text, mixed-content whitespace, extension XHTML and foreign namespaces |
| `EpubComplianceTest` | One/three chapters, same titles, empty body, null/blank metadata and fallback titles, absent cover, manifest/spine references, STORED first `mimetype`, direct PNG/JPEG, SVG/MathML properties |
| `EpubXhtmlWorkerTest` | Actual component serializers and `ContentJsonDecoder`, actual Worker and writer, one/two volumes, whole-book/batch-volume outputs, equal chapter/volume titles, empty chapters, ordered spine/nav/NCX, supplementary characters, PNG-to-JPEG output, repeated images and colliding old URI hashes |
| Existing `SourceExportWorkerTest` | Source/book identity isolation, same-name volume retention, task-local cleanup and rejected foreign selected-volume IDs |
| Existing `ContentDecodingContractTest` | Registration/decoding compatibility, extension fields, exact export IDs, invalid-entry policy, exception/cancellation behavior |

The Worker tests run under Robolectric API 27. Repository reads, image acquisition
and DocumentProvider output streams are controlled fixtures. A real PNG is
decoded to a bitmap at the mocked acquisition boundary; the production
ImageDownloader performs JPEG compression, and tests inspect its actual bytes.
These tests exercise the host wiring, but do not establish real network/Coil,
Android WorkManager scheduling, provider transactions or device behavior.

Five JVM EPUBs and seven Worker EPUBs cover the acceptance matrix. The original
library fixtures contained only `main` methods, so the initial `:epub:test`
reported no discovered tests. The first three new regression tests failed before
the fix. A pre-fix no-cover sample from this checkout produced RSC-001 and
RSC-017. The first Worker EPUBCheck run also exposed empty optional metadata;
this was corrected at the builder boundary rather than hidden in the fixture.

Opening the final files in Readium and an independent reader, Android device
tests, real SAF failure injection, large books and real-source export remain
unverified. A green EPUBCheck report does not prove these behaviors, text
completeness for unsupported components, or accessibility conformance.

## EXPORT-001 / #71 investigation

The current Worker already has BOOK and VOLUMES paths, a list of per-volume
builders, volume-indexed filenames and source/book/task-scoped temporary paths.
It is inaccurate to describe the implementation as lacking multi-volume support.
The upstream #491 attachment previously collected in the research snapshot had
no EPUB failure stack; the original device report is not reproduced here.

Fresh JVM probes against this branch's writer reproduced:

- `ChapterBuilder` with a title and no chapters/content throws
  `java.lang.Error: Missing 'content' or 'chapters'`. Worker catches Exception,
  so this input escapes the normal failure-result boundary. Empty *body*
  chapters are covered by #72; an empty *volume* needs #71 policy.
- Calling `build()` twice on the same builder with one chapter grows its spine
  from 1 to 3 items. `toOl()` appends to `contentChapters`, then `build()` appends
  again to `spineItems`. The current Worker calls each builder once; this is a
  builder-reuse defect, not proof of the upstream batch failure.

Source inspection also confirms remaining #71 work:

- `parseSrc` receives `includeImages` without enforcing it; repeated images
  still enqueue repeated downloads. Cover-resource URI hashing remains in the
  per-volume cover-selection path.
- Chapter IDs still use object/title hashes. Same-name fixtures pass, but this
  does not guarantee deterministic IDs or eliminate hash collisions for every
  chapter model. #71 should derive IDs from its ordered export plan.
- Decoder skips are existing compatibility behavior; #71 needs a completeness
  policy and missing-component/chapter diagnostics, not a silent policy change
  in this format fix.
- Batch output creates and copies target files sequentially. Late failure can
  leave earlier successful outputs or a partial current file; there is no
  per-volume completion/retry report or provider-independent atomic replace.
- Image resources are read wholly into memory by the writer. Large-book memory,
  cancellation and acquisition failures need their own tests.

Recommended #71 sequence: capture an ordered book/volume/chapter/resource plan;
validate empty/missing inputs and assign unique IDs; obtain resources and build
complete temporary EPUBs; verify before delivery; record each provider copy and
close result, clean up newly created failed outputs, and report partial batches.
An atomic rename can be used where the actual filesystem/provider supports it.
Android's SAF API does not promise atomic replacement across arbitrary providers
or a multi-file transaction. These changes are not implemented by #72.

## Current stable EPUB methods and tools

Official documentation and release endpoints were read on 2026-09-10. These are
different parts of a reliable EPUB workflow, not interchangeable exporters:

| Purpose | Current stable choice and rationale |
| --- | --- |
| Format target | [W3C EPUB 3.3](https://www.w3.org/TR/epub-33/): namespace-aware XHTML, explicit package manifest/reading-order spine/navigation, correct media types and OCF ZIP rules |
| Conformance | [EPUBCheck 5.3.0](https://github.com/w3c/epubcheck/releases/tag/v5.3.0), confirmed by its production README and latest release API; pin it in repeatable validation and retain per-resource JSON diagnostics |
| Android parsing/opening | [Readium Kotlin 3.3.0](https://github.com/readium/kotlin-toolkit/releases/tag/3.3.0); use Publication/reading order for import or reader acceptance. It is a reading toolkit, not a replacement authoring writer |
| Accessibility | [DAISY Ace 1.4.6](https://github.com/daisy/ace/releases/tag/v1.4.6); adds automated accessibility checks. Its documentation explicitly requires broader human evaluation; it does not replace EPUBCheck |
| Desktop inspection/editing | [Sigil 2.8.1](https://github.com/Sigil-Ebook/Sigil/releases/tag/2.8.1), a maintained EPUB 2/3 editor with rendered inspection; useful for diagnosis and reference files, not an Android runtime dependency |
| File delivery | [Android SAF documentation](https://developer.android.com/training/data-storage/shared/documents-files): inspect provider capabilities, respect URI grants and successful close, and do not assume overwrite/rename semantics across providers |

For this application, the narrowest stable solution is to keep PotatoEPUB,
enforce format invariants at generation boundaries, compare decoded content and
resource order, and validate actual host-produced files. Swapping the writer
does not solve Worker lifecycle, missing-source content or SAF delivery. Readium,
Ace and Sigil were researched here; none was added as a runtime dependency or
claimed as an executed reader/accessibility acceptance test.
