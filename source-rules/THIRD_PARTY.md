# Selector provenance

`src/main/kotlin/hnovel/rules/selector/` adapts four files from
hectorqin/legado, revision `da17bb2bed44f30b12a524c2457e32a20b16fa41`,
under GPL-3.0 (license text: `../source-compatibility/reference/LICENSE`).
Original paths: `app/src/main/java/io/legado/app/model/analyzeRule/`.

Changes: package relocation, removal of Android shrinker annotations,
TextUtils.join replaced with Kotlin joinToString, malformed rule Error converted
to IllegalArgumentException, and JSONPath failures propagated except missing paths.
The selectors' default-chain/index, attribute deduplication, HTML and interleave
semantics are retained. The raw checksummed test oracle remains unchanged.

The production parser, execution context, budgets, regex adapter and script port
are separate from these selectors. Sharing selector ancestry is not an independent
algorithmic oracle: tests compare both implementations with hand-derived fixtures,
and separately test host routing and deliberate error-policy differences.
