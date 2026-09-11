package indi.dmzz_yyhyy.lightnovelreader.ui.home.categories

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceCapability
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceSessionManager
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebSourceRegistry
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.DiscoveryPageViewModel
import javax.inject.Inject

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    registry: WebSourceRegistry,
    accounts: SourceSessionManager,
    saved: SavedStateHandle,
) : DiscoveryPageViewModel(registry, accounts, saved, SourceCapability.Categories)
