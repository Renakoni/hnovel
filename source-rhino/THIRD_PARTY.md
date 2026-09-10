# Runtime dependencies and adapted helpers

Rhino is pinned to 1.8.1. Crypto factories use Hutool crypto/core 5.8.22
(Mulan PSL v2), matching the compatibility reference's helper dependency.
Only explicit native-JS data methods wrap this dependency; its Java APIs are
not made visible to scripts. JCE providers determine algorithm availability.

`ScriptHtml.kt` adapts the pure `format`/`formatKeepImg` behavior from
`app/src/main/java/io/legado/app/utils/HtmlFormatter.kt` at hectorqin/legado
`da17bb2bed44f30b12a524c2457e32a20b16fa41`, under GPL-3.0-only (license text:
`../source-compatibility/reference/LICENSE`). The adaptation removes the
unused redirect-URL argument and Android application references. It retains
paragraph indentation, image-source selection and case-sensitive tag filtering.
These are compatibility formatting rules, not an HTML sanitizer.

Nested rules reuse source-rules; its selector provenance is documented in
`../source-rules/THIRD_PARTY.md`. Other Rhino boundary/port implementations
are independently written around the documented data contracts.
