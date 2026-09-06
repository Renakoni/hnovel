package indi.dmzz_yyhyy.lightnovelreader.ui.components

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import indi.dmzz_yyhyy.lightnovelreader.ui.home.HomeNavigateBar

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun LnrNavigationBar(
    showBottomBar: Boolean,
    selectedRoute: Any?,
    navController: NavController
) {
    val navInsets = WindowInsets.navigationBars
    val navHeight = navInsets.asPaddingValues().calculateBottomPadding()

    val showPlaceholder =
        !showBottomBar &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                navHeight > 0.dp

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        AnimatedVisibility(
            modifier = Modifier.align(Alignment.BottomCenter),
            visible = showBottomBar,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(
                    durationMillis = 420,
                    easing = FastOutSlowInEasing
                )
            ),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(
                    durationMillis = 380,
                    easing = FastOutSlowInEasing
                )
            )
        ) {
            HomeNavigateBar(
                selectedRoute = selectedRoute,
                controller = navController
            )
        }

        AnimatedVisibility(
            modifier = Modifier.align(Alignment.BottomCenter),
            visible = showPlaceholder,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(navInsets)
                    .background(
                        colorScheme.surface.copy(alpha = 0.75f)
                    )
            )
        }
    }
}
