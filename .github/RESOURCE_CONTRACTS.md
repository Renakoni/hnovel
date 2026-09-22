# Translation resource contracts

Run without Gradle:

```sh
python3 .github/scripts/test_check_resources.py
python3 .github/scripts/check_resources.py
```

The checker compares translatable `string`, `plurals`, and `string-array`
resources in `app/src/main/res/values` with Simplified Chinese (`values-zh-rCN`),
Traditional Chinese (`values-zh-rTW`), and generic Russian (`values-ru`). Add new
keys to all supported languages. Russian regional overrides are checked when
present, but cannot replace generic Russian coverage.

Checks include missing/defaultless/duplicate keys, resource types, `formatted`
attributes, argument positions and type families, common invalid format syntax,
array item contracts, and each plural branch. Argument order and repeated uses
may differ. English needs `one/other`, Chinese `other`, and Russian
`one/few/many/other`. Literal percent signs in text that is never formatted use
`formatted="false"` consistently across translations.

This is a resource contract check, not a full Java Formatter implementation,
call-site type analysis, or language-quality assessment. Android Lint and actual
resource/Compose tests remain necessary. Dynamic website content and plugin
messages are outside this XML check. Other modules and qualified resource
overrides beyond the listed directories are outside its coverage.

Identical text is reviewed, not automatically treated as missing translation.
The existing exceptions are brand/font names (`Bangumi`, `Source Han Serif`,
`LXGW WenKai`), the font sample `永 Aa`, the `r18` label, original source names
in `source_examples_*`, technical `ID`/`SHA-256`/HTTP labels, and templates that
contain only parameters, punctuation, units, or percentages (`source_page_*`,
`sources_import_problem`, `sources_check_summary`, `tts_factor`,
`update_notification_progress`). These are explicit resources, not English
fallback. Do not copy English interface sentences to satisfy coverage.

`RussianQuantityResourcesTest` checks actual Android selection for `ru` and
`ru-RU` on API 24/35, TTS counts 1/2/5/21, and integer/string/decimal formatting.
`PluginSignatureResourcesTest` checks the mixed integer/string signature label
in all supported languages. Compose tests cover representative long translated
source controls at 320dp with 1.6× font size; this does not assert that every
screen or device configuration has been visually tested.
