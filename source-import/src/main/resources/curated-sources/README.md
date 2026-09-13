# Curated novel text sources

Import [novels-v1.json](novels-v1.json) using the reader's existing JSON file or URL
import. Review the preview and approve the origins shown by the app. No source is
automatically installed or granted network access. Re-import the same file to
preview an update; retain the existing identity when replacing a definition.

| Source | Supported scope |
| --- | --- |
| Fanqie | Official anonymous text search and public chapters; audio results and paid/locked chapters are excluded. |
| SF | Official mobile search's single result page, mobile directories and public chapters; later search pages are empty and VIP links are excluded. |
| Kanunu | The four Chinese classical novels only, with title/author filtering and a discovery list; response decoding follows the page's GBK/GB2312 declaration. |

The definitions are independently maintained. One definition represents each site;
aliases in third-party collections are not additional sources. This collection does
not duplicate sites supplied by the maintained LNRP imports. Existing user
definitions with other exact identity strings still require an explicit import
decision; this file does not rewrite their identity or settings.

Fanqie decoding targets font version `dc027189e0ba4cd`. Its 362 mapped glyphs were
independently matched by decomposed outlines and advance widths to Adobe
Source Han Sans SC Normal 2.002 (OFL-1.1). The site font SHA-256 is
`ba1a867e395a7f8bca99dd13a0d6428b8f12fc9c38e3a0b2c3522b98f41763fe`;
the reference OTF SHA-256 is
`69a090057ee75936451397c038da6d32194e45bbf878ef674dddc0d5f0621ce5`.
Fonts and chapter text are not redistributed. Unknown font versions or unmapped
private-use characters fail instead of silently mixing mappings. To update the
table, repeat the glyph comparison and the text regressions against actual pages.

The text rules remove inline images where present; image-only pages have no readable
text. Site availability, account-only material and later site changes remain outside
the verified public-text scope. This direct web adapter does not make the separate
complex Fanqie JavaImporter source executable.

The rules keep the normal execution budgets: Fanqie uses its canonical link for the
directory URL and inspects font URLs only in styles; SF uses the smaller mobile
directory; Kanunu selects candidate novel links before script processing. Large or
changed pages can still fail the reader's existing limits.

Validation uses deterministic source-content tests and an opt-in Android
`CuratedSourceExecutionInstrumentedTest`. For live tests, provide fresh public DNS
through `curatedSourcesDnsBase64` and set `liveCuratedSources=true`. That DNS control
belongs only to disposable tests; production keeps its normal address validation.
On 2026-09-13, API 35 reached search results at all three sites and read public text
from five fixed books with that control. This is not a claim that production Fake-IP
VPN resolution works, or that every book/chapter at these sites is available.
