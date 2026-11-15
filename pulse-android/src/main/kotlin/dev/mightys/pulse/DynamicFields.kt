package dev.mightys.pulse

import android.content.res.Configuration
import java.util.Locale

/** Raw platform readings behind the values that can change while the app runs. A null reading is left out. */
internal data class RawState(
    val orientation: Int, val uiMode: Int, val layoutDirection: Int, val fontScale: Float, val fontWeightAdjustment: Int?,
    val smallestWidthDp: Int, val isWatch: Boolean, val isAutomotive: Boolean,
    val widthPx: Int, val heightPx: Int, val densityDpi: Int, val density: Float, val displayCount: Int?,
    val animatorScale: Float?, val transitionScale: Float?, val inversion: Int?, val highContrast: Int?, val daltonizer: Int?,
    val appLanguage: String?, val preferredLanguage: String?, val region: String?,
)

internal object DynamicFields {
    @Suppress("DEPRECATION")
    fun fields(raw: RawState): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val type = raw.uiMode and Configuration.UI_MODE_TYPE_MASK
        result["deviceType"] = when {
            type == Configuration.UI_MODE_TYPE_TELEVISION -> "tv"
            raw.isWatch || type == Configuration.UI_MODE_TYPE_WATCH -> "watch"
            raw.isAutomotive -> "car"
            raw.smallestWidthDp >= 600 -> "tablet"
            else -> "phone"
        }
        when (raw.orientation) {
            Configuration.ORIENTATION_PORTRAIT -> result["orientation"] = "portrait"
            Configuration.ORIENTATION_LANDSCAPE -> result["orientation"] = "landscape"
            Configuration.ORIENTATION_SQUARE -> result["orientation"] = "square"
        }
        result["colorScheme"] = if (raw.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        result["layoutDirection"] = if (raw.layoutDirection == 1) "rtl" else "ltr"
        if (raw.fontScale > 0f) result["fontScale"] = AndroidMetadata.decimal(raw.fontScale)
        raw.fontWeightAdjustment?.let {
            result["fontWeightAdjustment"] = it.toString()
            result["boldText"] = (it > 0).toString()
        }
        if (raw.density > 0f && raw.widthPx > 0 && raw.heightPx > 0) {
            result["screenWidth"] = Math.round(raw.widthPx / raw.density).toString()
            result["screenHeight"] = Math.round(raw.heightPx / raw.density).toString()
            result["screenScale"] = AndroidMetadata.decimal(raw.density)
            result["screenWidthPx"] = raw.widthPx.toString()
            result["screenHeightPx"] = raw.heightPx.toString()
        }
        if (raw.densityDpi > 0) result["screenDpi"] = raw.densityDpi.toString()
        raw.displayCount?.takeIf { it > 0 }?.let { result["screenCount"] = it.toString() }
        if (raw.animatorScale != null && raw.transitionScale != null) {
            result["reduceMotion"] = (raw.animatorScale == 0f && raw.transitionScale == 0f).toString()
        }
        raw.transitionScale?.let { result["reduceTransparency"] = (it == 0f).toString() }
        raw.inversion?.let { result["invertColors"] = (it == 1).toString() }
        raw.highContrast?.let { result["increaseContrast"] = (it == 1).toString() }
        raw.daltonizer?.let { result["differentiateWithoutColor"] = (it == 1).toString() }
        raw.appLanguage?.takeIf { it.isNotBlank() }?.let { result["language"] = it.lowercase(Locale.ROOT) }
        raw.preferredLanguage?.takeIf { it.isNotBlank() }?.let { result["preferredLanguage"] = it.lowercase(Locale.ROOT) }
        raw.region?.takeIf { it.isNotBlank() }?.let { result["region"] = it.uppercase(Locale.ROOT) }
        return result
    }
}
