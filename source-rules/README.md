# Rule evaluation boundary

Production JVM code for #84, callable independently of Android. `RuleEvaluator`
accepts text/nodes/capture groups, a field location, an explicit request context,
an output kind and a budget. It returns a value (including empty) or a structured
stage/field/offset/error code. Error objects contain neither content nor credentials.

`ContentMarkup` converts already-extracted chapter HTML into ordered text/image
values inside the worker. It uses an iterative Jsoup traversal, without Rhino or
source libraries. Block elements and `<br>` keep paragraph boundaries; inline
spacing, single entity decoding and image positions are preserved. Script/style/
noscript subtrees are ignored. Image URLs remain raw for the content owner to
resolve against the chapter URL. Input/output and cooperative execution budgets
still apply, with at most 16,384 visited nodes and the rule budget's default depth
of 64. Parsing has pre/post checks and remains behind the process deadline.

`RuleContext` copies source/book/chapter input variables, resolves nearest nonempty
values first, and owns request-local writes. Reuse it only for fields in the same
request/book; create a fresh context for concurrent requests. The later host broker
decides which writes, if any, to persist. Source IDs and book IDs never come from
a current-source singleton.

Supported routing: Jsoup default chains and CSS; JSONPath, JSON node round trips
and embedded JSONPath templates; XPath over HTML/XML; colon-prefixed regex element
extraction (AllInOne/OnlyOne), capture interpolation, regex chains and replacement;
ordered &&/||/%% composition; @put/@get; rule templates and script stages.
Script templates and `<js>`/`@js:` payloads cross `RuleScriptPort` with explicit
intermediate values. An absent port reports ScriptPortUnavailable. This module
does not instantiate Rhino or expose a network client, repository or Java bridge.

The fixed target is hectorqin/legado@da17bb2bed44f30b12a524c2457e32a20b16fa41.
See THIRD_PARTY.md for selector ancestry. Deliberate/fixed-profile details:

- `%%` uses the first nonempty branch's length, retaining the pinned uneven-tail
  truncation. Attribute results are deduplicated by the selector. Scalar HTML URL
  uses the first match, an empty scalar URL uses baseUrl, URL lists deduplicate.
- Regex OnlyOne retains the reference's unmatched optional-group failure, returned
  as a structured Select/NullPointerException; AllInOne emits an empty capture.
- Invalid replacement patterns retain the reference's literal fallback; trailing
  `###` extracts and replaces the first match rather than retaining its surroundings.
- Missing JSON paths can fall back. Invalid JSON/selector syntax is an explicit
  failure instead of the reference's debug-log-and-empty behavior. Unknown script
  requirements never silently become empty data.
- Composition and JS/template boundaries are scanned with nesting/escapes preserved.
  JavaScript payloads are passed through; full JS syntax checking belongs to #87.

Budgets cover rule/input/output size, nesting, operation counts and cooperative
deadlines. Regex extraction/replacement use a budget-checking CharSequence even
inside backtracking. Third-party DOM/JSON/XPath calls have pre/post checks, not
guaranteed hard interruption. **Call this synchronous evaluator in the future
isolated execution service (#86), never on a UI thread for untrusted input.**
The Android import/runtime path is not enabled by this PR. These JVM tests do not
prove process isolation, hard termination of native/library code, or WebView safety.

Validation: `gradlew.bat :source-rules:test :source-compatibility:test`.
The latter executes the production adapter against all 16 pinned-selector cases;
the five script/host cases remain reference contracts for their later owners.
Additional evaluator tests cover routing, intermediate nodes, scopes, replacement,
relative URLs, script boundaries, precise errors, and budget exits. Expected values
are hand-derived, and raw vendored oracle checksums remain enforced.
