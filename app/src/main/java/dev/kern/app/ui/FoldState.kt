package dev.kern.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/**
 * The four first-class display modes (docs/05). Derived from window size + posture —
 * never from device type, per Android's landscape-first foldable guidance.
 */
enum class DisplayMode {
    /** Folded: the squat 5.5" cover screen. One-handed companion surfaces. */
    Cover,

    /** Unfolded flat: the 7.6" 4:3 inner screen. The main IDE canvas. */
    Unfolded,

    /** Half-folded, hinge horizontal: editor above the crease, terminal below. */
    Tabletop,

    /** DeX / external display / very wide window: desktop behaviour, no mobile chrome. */
    Desktop,
}

/**
 * Posture plus the geometry needed to lay out around the hinge.
 *
 * @param hingeBounds hinge rectangle in window pixels, or null when there is no
 *   separating fold. Content must never be placed inside it.
 */
data class FoldState(
    val mode: DisplayMode = DisplayMode.Unfolded,
    val hingeBounds: Rect? = null,
    val isSeparating: Boolean = false,
) {
    val isTabletop: Boolean get() = mode == DisplayMode.Tabletop
}

/** Width breakpoints (dp). Cover screen lands in Compact; the inner screen in Expanded. */
private const val MEDIUM_WIDTH_DP = 600
private const val DESKTOP_WIDTH_DP = 1200

@Composable
fun rememberFoldState(): FoldState {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val configuration = LocalConfiguration.current

    var fold by remember { mutableStateOf<FoldingFeature?>(null) }
    LaunchedEffect(activity) {
        val act = activity ?: return@LaunchedEffect
        WindowInfoTracker.getOrCreate(act).windowLayoutInfo(act).collect { info ->
            fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
        }
    }

    val widthDp = configuration.screenWidthDp
    val feature = fold
    val halfOpenHorizontal = feature != null &&
        feature.state == FoldingFeature.State.HALF_OPENED &&
        feature.orientation == FoldingFeature.Orientation.HORIZONTAL

    val mode = when {
        // Tabletop wins over size: it is a deliberate physical stance.
        halfOpenHorizontal -> DisplayMode.Tabletop
        widthDp >= DESKTOP_WIDTH_DP -> DisplayMode.Desktop
        widthDp >= MEDIUM_WIDTH_DP -> DisplayMode.Unfolded
        else -> DisplayMode.Cover
    }

    return FoldState(
        mode = mode,
        hingeBounds = feature?.takeIf { it.isSeparating }?.bounds?.let {
            Rect(it.left, it.top, it.right, it.bottom)
        },
        isSeparating = feature?.isSeparating == true,
    )
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
