package indi.dmzz_yyhyy.lightnovelreader.data.update

import kotlinx.coroutines.flow.MutableStateFlow

interface UpdateParser {
    fun parser(updatePhase: MutableStateFlow<String>): Release?
}