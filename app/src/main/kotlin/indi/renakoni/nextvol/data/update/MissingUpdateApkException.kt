package indi.renakoni.nextvol.data.update

import java.io.IOException

class MissingUpdateApkException(val archiveName: String) :
    IOException("APK not found in update archive [$archiveName]")
