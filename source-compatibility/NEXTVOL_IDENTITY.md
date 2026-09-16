# NextVol application identity (#231)

NextVol is installed as a separate application. Its host Kotlin/AIDL namespace is
`indi.renakoni.nextvol`, and the installed name is `NextVol` in every locale and
build variant. Icons and other visual design remain outside this change.

| Build | Application ID | Installed name |
| --- | --- | --- |
| release / benchmark | `indi.renakoni.nextvol` | NextVol |
| debug | `indi.renakoni.nextvol.debug` | NextVol |
| snapshot | `indi.renakoni.nextvol.snapshot` | NextVol |

The host Application, database, Compose app/theme and navigation entry point use
NextVol class names. Kotlin/AIDL source paths, generated R/BuildConfig references,
ProGuard rules, benchmark fixtures, CI class selectors and current reproduction
commands follow the host namespace. FileProvider and AndroidX startup authorities
continue to derive from the application ID. Gradle/APK names, new backup filenames
and the saved-picture directory use NextVol.

## Installation and data

The old application can remain installed. Android gives the two applications
separate UIDs, private directories, Keystore entries and WorkManager databases.
This change does not offer an in-place upgrade or automatic private-data transfer.
There is no old launcher alias, old Worker class map or copy of the old queue.

Room table/column names and the `light_novel_reader_database` storage filename are
unchanged. Renaming the host database class does not change these persistent
contracts. A new installation creates its own database and Hilt Worker factory.

Existing `.lnr` files continue to use ZIP entry `data` containing `AppLocalData`
version 1 CBOR fields. They do not serialize host class names or the WorkManager
database. Import validates source/book/chapter relationships and retires any old
download attempts using the existing implementation. This is explicit backup
import, not a claim that account keys, cached images or old Android URI grants
automatically transfer between the apps.

The [pre-migration fixture](../app/src/test/resources/backups/README.md) was
exported by the old namespace and is imported by `SourceIdentityRoomTest` after
the rename. It covers mixed-source associations, reading progress, statistics and
download ownership independently of a new-code-only round trip.

## Names intentionally retained

- The public `io.nightfish.lightnovelreader.api` API, `LightNovelReaderPlugin`,
  plugin discovery action, `lightnovelreader://` import scheme and `.lnrp` files.
  These are existing external protocols; a package rename does not version them.
- `lightnovelreader:Wenku8`, content component identifiers, `lnr1` storage keys,
  `.lnr` backup extension/MIME type and existing settings paths. Changing these
  would change data ownership and break compatibility.
- Upstream attribution, licenses, provider credit and actual upstream URLs.
  Captured browser JSON reports retain the package they measured; current runner
  scripts and reproduction commands use the new package. The tracked IDE database
  connection is a developer's old local path, not a runtime application identity.

## Deferred services

The user explicitly deferred upstream service changes to
[#232](https://github.com/Renakoni/hnovel/issues/232). UpdateCheckRepository,
APIParser/GithubParser targets and channels, Matomo endpoint/site/application
identifier, donation/community/translation links and the upstream F-Droid page
keep their current behavior. Their host imports move with the code, but their
configuration is not migrated or retired here. NextVol has no release channel
merely because its application ID changed.

The source runtime, parsers, network modes, browser environment and request UA are
unaffected by this identity change. #220 is delivered separately by PR #230.

## Rebuilding an existing checkout

If the checkout was compiled under the previous host package, run `:app:clean`
once before rebuilding. Hilt/KSP can leave generated roots for the removed
Application class in an incremental build. The clean build must generate only
the NextVol Application, Worker factory keys and AIDL descriptors; old host class
aliases are unnecessary for this separate application identity.
