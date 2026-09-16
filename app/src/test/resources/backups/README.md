# Backup before the NextVol namespace migration

`before-nextvol.lnr` was exported on 2026-09-16 by the production
`LocalDataManager`, CBOR serializers and `writeAppLocalData` at main
`e9103ab624d06734f527d530d3fb4eae2bf1e4ce`, while their package was still
`indi.dmzz_yyhyy.lightnovelreader`. It contains synthetic test data only.

SHA-256: `f84289f58fa9ae75ecb2d9e82ab0124f8263c3ee847622c0887cab9d44e65036`.

The ZIP's `data` entry contains an `AppLocalData` version 1 document with:

- Two books with remote ID `123`, owned by `lightnovelreader:Wenku8` and `site:b`.
- One mixed bookshelf, source-bound chapter/volume references, current reading
  list, chapter progress `0.5`, and two reading events totaling one minute.
- A Wenku8 download with revision `before-nextvol-revision` and chapter `9`
  with signature `before-nextvol-signature`. The export strips the active
  attempt; it does not contain a WorkManager database or private image files.

`SourceIdentityRoomTest.preNextVolBackupRestoresSourceAssociationsAndDownloadOwnership`
imports these fixed bytes using the renamed classes and verifies the restored
associations and reading-cache protection. Keep the fixture fixed: regenerating
it with NextVol would stop testing the old producer/new consumer boundary.
