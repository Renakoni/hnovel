package indi.renakoni.nextvol.data.update

import kotlinx.coroutines.flow.MutableStateFlow

interface UpdateParser {
    fun parser(updatePhase: MutableStateFlow<UpdatePhase>): Release?
}