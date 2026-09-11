# Source compatibility reference tests

This is a **test-only JVM module**, delivering [VNR-10 / #82](https://github.com/Renakoni/hnovel/issues/82). It has no production sources and the Android app does not depend on it. Its tests now depend on the production `source-rules` module to verify #84. It neither imports user sources into the app nor loads APK plugins.

```powershell
.\gradlew.bat :source-compatibility:test --console=plain --max-workers=2
```

The existing `JVM unit tests` CI job runs this task alongside the app tests. It does not add a release, device, or emulator workflow. Dependency resolution needs the usual Maven access on an empty cache; fixture execution uses no external site, account, local private file, or network server.

## What a green result proves

- `pinned-selector`: the checked-in, unmodified Legado selector source produces the manually reviewed expected result for that specific case.
- `rhino-contract`: Rhino executes a synthetic script against a recording **host double**. This validates the test contract, not the real Legado Java bridge, login implementation, or browser.
- `mixed-contract`: a pinned HTML selector feeds a synthetic script. This does not exercise upstream `AnalyzeRule` routing or the complete `WebBook` pipeline.

The first corpus has 21 executable cases and six synthetic source definitions. `coverage.json` maps the broader target to implementation Issues and future test IDs, and records current per-feature product evidence. URL/HTTP/storage retain their explicit partial-acceptance boundaries. No skipped test is used to make missing product functionality look green; JVM host doubles are not Android browser or process-isolation evidence.

`ReferenceRunner` calls the pinned selectors directly. It intentionally does not reimplement the whole rule interpreter as a supposedly independent oracle. The three small JVM shims replace only an Android shrinker annotation, `TextUtils.join`, and disabled debug logging. They do not supply fake parser results.

## Fixture layout

- `src/test/resources/cases.json`: operation, input, hand-derived expected value/error, and evidence class for every runnable case.
- `src/test/resources/fixtures/`: tiny HTML/JSON/text inputs and trusted synthetic JavaScript. `host.js` only returns configured responses and records actions. It has no network or filesystem access.
- `src/test/resources/sources.json`: simple HTML, JSON, mixed, shared script, login, and dynamic-discovery source definitions. The HTML and JSON cases read actual fields from these definitions; other scripts are explicit contract scenarios, not full source execution.
- `src/test/resources/coverage.json`: standard profile, target extension profile, unknown-profile policy, feature owners, verification status, and planned conformance IDs.
- `src/test/resources/advanced-inventory.json`: only field shapes and member names observed during private-sample research. No original script body, private endpoint, authentication value, or `.lnrp` is included.
- `reference/provenance.json`: upstream revision, paths, and SHA-256 checksums of the vendored files.

Results are in `build/reports/tests/test` and `build/test-results/test`. The coverage inventory is copied to `build/reports/source-compatibility/coverage.json`; this is a per-feature status inventory, **not a report that all product features passed**. All three, plus source-rules test reports, are uploaded by the existing CI job.

## Updating the corpus and adding the product adapter

1. Add a small synthetic input and independently derive its expected output; describe the provenance in the case. Do not take expected output from the product implementation being tested.
2. Link the case to a coverage feature and its owning Issue. Unimplemented platform/host behavior retains a planned test ID and explicit pending evidence.
3. Run the pinned reference or state that the case is only a contract scenario. Do not relabel a host double as upstream behavior.
4. When a real production operation lands, compare its result with the same independently reviewed expectation and applicable reference operation. Add the production dependency/adapter to this test module then, and replace the all-planned integrity gate with explicit per-feature execution results. Production code must never depend on this reference module.
5. A reference upgrade must update the revision, dependency versions, raw checksums, expectations, and documented behavioral differences together. Keep deliberate deviations explicit, especially reference bugs.

Mismatch, unknown-operation, missing-resource, duplicate-ID, and broken ownership checks fail the suite. Vendored Kotlin files retain LF line endings so checksums are identical on Windows and Linux.

The synthetic Rhino runner applies an instruction limit to stop accidental fixture loops. This is **not** the future Android execution sandbox, and this task is not an entry point for evaluating untrusted user scripts.

See [the baseline and known limits](../docs/source-compatibility-baseline.md) and [reference licensing](reference/NOTICE.md).

## Production rule evidence (#84)

`ProductRuleFixtureTest` executes all 16 pinned-selector cases through the production evaluator and compares them with both reviewed expectations and the unchanged oracle. `source-rules` also tests the routing, replacement, context and budget contracts. The transplanted selectors share upstream ancestry with the oracle; this is explicitly not an independent algorithmic implementation. Full upstream AnalyzeRule, JS, network and browser compatibility are not inferred from these selector tests.

## Broker backend evidence (#85)

Tests now depend on production `source-network`. `ProductRequestContractTest` compiles the six existing synthetic search URLs without network access; `SourceBrokerTest` separately exercises real local MockWebServer requests, policy and state isolation. This is backend contract evidence, not a full upstream AnalyzeUrl/JavaScript/browser oracle. Process binding and script entry points remain pending under #86/#87/#89.

## Production import evidence (#83)

`ProductImportFixtureTest` previews and explicitly commits the six existing synthetic definitions through `source-import`. Its tests also cover indexed validation errors, selection/conflict decisions, identity preservation, flags, disk snapshots and local broker downloads. Unknown fields and external customOrder are retained; customOrder does not change the host tab order. Importing a definition does not execute its rules or establish runtime compatibility.

## Production discovery evidence (#91)

`RuleDiscoveryTest`, `ScriptDiscoveryTest`, and the app's `RuleDiscoveryProviderTest` execute the finite source catalogue/action protocol through production entry points. Host state and Compose tests cover one source per tab, filter snapshots, cancellation and source-local refreshes. The four #91 feature entries refer to that bounded discovery/settings contract, not every reader callback or overload in a Legado fork. See [the protocol, provenance, and deliberate differences](../source-content/DISCOVERY.md), including `infoMap.save` commit timing and the product-defined `exploreScreen` / `upConfig` mappings. The historical private-sample inventory remains shape evidence, not proof of live-source compatibility.
