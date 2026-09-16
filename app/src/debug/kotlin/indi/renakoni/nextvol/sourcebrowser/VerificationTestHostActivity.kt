package indi.renakoni.nextvol.sourcebrowser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import indi.renakoni.nextvol.data.web.rules.SourceVerificationCoordinator
import indi.renakoni.nextvol.ui.SourceVerificationHost

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface SourceVerificationDebugEntryPoint {
    fun registry(): indi.renakoni.nextvol.data.web.WebSourceRegistry
    fun coordinator(): SourceVerificationCoordinator
    fun revisions(): indi.renakoni.nextvol.data.web.rules.SourceRevisionUpdates
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
