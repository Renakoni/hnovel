package indi.dmzz_yyhyy.lightnovelreader.sourcebrowser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import indi.dmzz_yyhyy.lightnovelreader.data.web.rules.SourceVerificationCoordinator
import indi.dmzz_yyhyy.lightnovelreader.ui.SourceVerificationHost

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface SourceVerificationDebugEntryPoint {
    fun registry(): indi.dmzz_yyhyy.lightnovelreader.data.web.WebSourceRegistry
    fun coordinator(): SourceVerificationCoordinator
}

/** Debug-only host for the production verification UI, without unrelated onboarding. */
class VerificationTestHostActivity : ComponentActivity() {
    companion object { var coordinator: SourceVerificationCoordinator? = null }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val owner = checkNotNull(coordinator)
        setContent { MaterialTheme { SourceVerificationHost(owner) } }
    }
}
