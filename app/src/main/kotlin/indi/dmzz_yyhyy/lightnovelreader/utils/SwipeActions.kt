package indi.dmzz_yyhyy.lightnovelreader.utils

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import indi.dmzz_yyhyy.lightnovelreader.R
import me.saket.swipe.SwipeAction

data class BaseSwipeAction(
    val iconRes: Int,
    val backgroundColor: Color,
    val contentDescription: String? = null
) {
    fun toSwipeAction(onSwipe: () -> Unit): SwipeAction {
        return SwipeAction(
            icon = {
                Icon(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    painter = painterResource(iconRes),
                    tint = MaterialTheme.colorScheme.surface,
                    contentDescription = contentDescription
                )
            },
            background = backgroundColor,
            onSwipe = onSwipe
        )
    }
}


val addToBookshelfAction = BaseSwipeAction(
    iconRes = R.drawable.bookmark_add_24px,
    backgroundColor = Color(0xff2ECC71)
)

@Suppress("UNUSED")
val removeFromBookshelfAction = BaseSwipeAction(
    iconRes = R.drawable.delete_forever_24px,
    backgroundColor = Color(0xffE74C3C)
)

@Suppress("UNUSED")
val expandAction = BaseSwipeAction(
    iconRes = R.drawable.expand_circle_down_24px,
    backgroundColor = Color(0xffF1C40F)
)

val pinAction = BaseSwipeAction(
    iconRes = R.drawable.keep_24px,
    backgroundColor = Color(0xff007AFF)
)

val unpinAction = BaseSwipeAction(
    iconRes = R.drawable.keep_24px,
    backgroundColor = Color(0xffE74C3C)
)