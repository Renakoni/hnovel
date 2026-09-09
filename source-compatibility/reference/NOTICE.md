# Legado reference source notice

The five files under `kotlin/io/legado/app/model/analyzeRule/` are unmodified copies from:

- Repository: https://github.com/hectorqin/legado
- Revision: `da17bb2bed44f30b12a524c2457e32a20b16fa41`
- Original directory: `app/src/main/java/io/legado/app/model/analyzeRule/`
- Authors: the Legado contributors; original source comments and attribution are retained.
- License: GNU General Public License version 3, reproduced in `LICENSE` from the same revision. The repository license is not overridden by the parent project's Apache license.

The pinned source and the test program incorporating it are used as a separate GPL reference test program. They are not linked into or distributed inside the Android APK. The application's source license is not a grant to relicense these reference files. Anyone redistributing this reference program must preserve the GPL notices and provide corresponding source under the applicable terms.

`provenance.json` records exact source paths and SHA-256 checksums. No vendor algorithm has been edited. Android-only marker/log/join dependencies are supplied by three explicitly documented test shims outside this directory.

Oracle dependencies match the pinned `gradle/libs.versions.toml`: Jsoup 1.16.2, JsonPath 2.10.0, JsoupXpath 2.5.3, Gson 2.13.2, Rhino 1.8.1. The pinned Rhino module's old 1.7.14 file dependency is commented out; this suite follows its active Maven dependency. Maven dependencies retain their respective licenses and notices.

The reference is a preserved fork snapshot, not a claim to represent the current official application or every third-party rule dialect. No Yuedu source collection, private rule JSON, APK/lnrp binary, or upstream Rhino host-wrapper source is vendored here.
