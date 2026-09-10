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


`ScriptText.kt` copies the T2S exclusion policy from `utils/ChineseUtils.kt`
at the same pinned Legado revision. Conversion uses the reference dependency
`com.github.liuyueyi.quick-chinese-transfer:quick-transfer-core:0.2.16`
(Apache-2.0), including its packaged dictionaries. No network or dictionary
update occurs during script execution.

The eight `src/main/java/hnovel/rhino/charset` files come from that revision's
`app/src/main/java/io/legado/app/lib/icu4j` directory. Only the package name,
Android annotations/imports and unused ParcelFileDescriptor overload change;
the detector algorithms and recognition tables are retained. Unicode/IBM
notices are preserved. The Unicode permission notice is reproduced below.
This subset avoids importing a different detector or the whole ICU runtime.

`font/QueryTTF.java` is the pinned `model/analyzeRule/QueryTTF.java` implementation under
GPL-3.0-only (see `../source-compatibility/reference/LICENSE`). Adaptations
relocate its package, remove Android's Keep annotation, validate table/glyph extents before allocations, correct format-4 glyph-array zero/delta/modulo handling and validate its ranges, and add bounded format-12 cmap decoding (an intentional extension over the pinned parser). `ScriptFonts` is a
native-JS facade; parser maps and Java objects are not script capabilities.

Android archive decoding uses `me.zhanghai.android.libarchive:library:1.1.6`,
the reference's pinned dependency. Its Android bindings are Apache-2.0; bundled
libarchive/codecs retain their upstream licenses, shipped with the dependency
and recorded by the app's generated dependency license inventory. See
https://github.com/zhanghai/libarchive-android/tree/v1.1.6 . The app adapter is
independent code using memory buffers and never opens archive-provided paths.

Test fixtures are generated original data (three triangle-glyph fonts and a
single synthetic chapter in ZIP/RAR/7z). `src/test/resources/fixtures/generate.py`
records the construction and tool versions; these tools are not runtime or
build dependencies.

COPYRIGHT AND PERMISSION NOTICE (ICU 58 and later)

Copyright © 1991-2016 Unicode, Inc. All rights reserved.
Distributed under the Terms of Use in http://www.unicode.org/copyright.html

Permission is hereby granted, free of charge, to any person obtaining
a copy of the Unicode data files and any associated documentation
(the "Data Files") or Unicode software and any associated documentation
(the "Software") to deal in the Data Files or Software
without restriction, including without limitation the rights to use,
copy, modify, merge, publish, distribute, and/or sell copies of
the Data Files or Software, and to permit persons to whom the Data Files
or Software are furnished to do so, provided that either
(a) this copyright and permission notice appear with all copies
of the Data Files or Software, or
(b) this copyright and permission notice appear in associated
Documentation.

THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF
ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT OF THIRD PARTY RIGHTS.
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS
NOTICE BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL
DAMAGES, OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE,
DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER
TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
PERFORMANCE OF THE DATA FILES OR SOFTWARE.

Except as contained in this notice, the name of a copyright holder
shall not be used in advertising or otherwise to promote the sale,
use or other dealings in these Data Files or Software without prior
written authorization of the copyright holder.

ScriptUrls implements the pinned utils/JsURL.kt data contract. ScriptTools.toNumChapter adapts JsExtensions.toNumChapter, AppPattern.titleNumPattern and StringUtils stringToInt/chineseNumToInt at the same GPL-3.0 revision; it preserves first-match output and 32-bit arithmetic. DOM dispatch uses the pinned Jsoup 1.16.2 (MIT) dependency. Response metadata uses the repository OkHttp/Okio dependencies (Apache-2.0), never the host client.
