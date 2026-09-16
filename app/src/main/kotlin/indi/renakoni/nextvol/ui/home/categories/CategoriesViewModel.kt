package indi.renakoni.nextvol.ui.home.categories

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.web.SourceCapability
import indi.renakoni.nextvol.data.web.SourceSessionManager
import indi.renakoni.nextvol.data.web.WebSourceRegistry
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryPageViewModel
import javax.inject.Inject

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    registry: WebSourceRegistry,
    accounts: SourceSessionManager,
    saved: SavedStateHandle,
) : DiscoveryPageViewModel(registry, accounts, saved, SourceCapability.Categories)
