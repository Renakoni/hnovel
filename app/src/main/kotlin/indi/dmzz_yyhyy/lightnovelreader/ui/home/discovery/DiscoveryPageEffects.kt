package indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.navigation.NavBackStackEntry
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryAction
import kotlinx.serialization.json.Json
import java.util.UUID

/** Both source pages use the same cancellation and foreground browser ownership rules. */
@Composable
internal fun DiscoveryPageEffects(model: DiscoveryPageViewModel, entry: NavBackStackEntry) {
    val nav = LocalNavController.current
    val environment = discoveryEnvironment()
    LifecycleStartEffect(model, environment) {
        model.environment(environment)
        model.setActive(true)
        onStopOrDispose {
            // The owned browser opens an Activity, not a navigation destination. Do not cancel
            // it merely because it covers this page; navigating elsewhere still cancels it.
            model.setActive(false, retainBrowser = nav.currentBackStackEntry?.id == entry.id)
        }
    }
    DisposableEffect(model, nav, entry) {
        onDispose {
            // Navigation can remove the composition after onStop already retained the browser.
            if (nav.currentBackStackEntry?.id != entry.id) model.setActive(false)
        }
    }
    LaunchedEffect(model) {
        model.commands.collect { command ->
            if (model.accepts(command)) when (val action = command.action) {
                is DiscoveryAction.Results -> nav.navigate(Route.Main.DiscoveryResults(command.source.namespace,
                    command.source.id, action.target, action.title, UUID.randomUUID().toString(),
                    filtersJson = Json.encodeToString(command.values)))
                DiscoveryAction.Login, DiscoveryAction.Settings -> nav.navigate(Route.Main.Settings.SourceDetail(
                    command.source.namespace, command.source.id, action == DiscoveryAction.Login))
                is DiscoveryAction.Browser -> model.openBrowser(command)
            }
        }
    }
}
