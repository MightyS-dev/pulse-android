package dev.mightys.pulse

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Colors of the built-in screens: a neutral light and dark pair that follows the system. */
internal class ControlPalette(val night: Boolean) {
    val surface: Int = if (night) 0xFF121214.toInt() else 0xFFFFFFFF.toInt()
    val text: Int = if (night) 0xFFEDEDF0.toInt() else 0xFF1B1B1F.toInt()
    val secondary: Int = if (night) 0xFFB4B4BC.toInt() else 0xFF55555D.toInt()
    val accent: Int = if (night) 0xFF8AB4F8.toInt() else 0xFF1558C0.toInt()
    val onAccent: Int = if (night) 0xFF0B1B33.toInt() else 0xFFFFFFFF.toInt()
    val scrim: Int = 0x99000000.toInt()
    val warning: Int = 0xFFFFD54F.toInt()
    val onWarning: Int = 0xFF1B1B1F.toInt()

    companion object {
        fun of(context: Context): ControlPalette =
            ControlPalette(context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    }
}

/** What the buttons of a screen do. */
internal class ControlActions(val primary: () -> Unit, val secondary: () -> Unit, val outside: () -> Unit)

/** The views of the built-in screens. Plain android.widget classes and the system theme only: no AppCompat, no images. */
internal object ControlViews {
    private const val MAX_WIDTH_DP = 560

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    /** A column that never grows wider than a phone-sized card, so tablets get a centered panel instead of stretched text. */
    private class Column(context: Context) : LinearLayout(context) {
        private val max = context.dp(MAX_WIDTH_DP)
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val spec = if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && width > max) MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY) else widthMeasureSpec
            super.onMeasure(spec, heightMeasureSpec)
        }
    }

    private fun label(context: Context, text: String?, sizeSp: Float, color: Int, bold: Boolean = false, heading: Boolean = false): TextView = TextView(context).apply {
        this.text = text.orEmpty()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp); setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
        if (heading && Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun button(context: Context, text: String, palette: ControlPalette, filled: Boolean, onClick: () -> Unit): Button = Button(context).apply {
        this.text = text; isAllCaps = false; maxLines = Int.MAX_VALUE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(if (filled) palette.onAccent else palette.accent)
        minimumHeight = context.dp(48); minHeight = context.dp(48)
        setPadding(context.dp(20), context.dp(12), context.dp(20), context.dp(12))
        background = GradientDrawable().apply { cornerRadius = context.dp(12).toFloat(); setColor(if (filled) palette.accent else Color.TRANSPARENT) }
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun stack(parent: ViewGroup, vararg children: View) { for (child in children) if (child.visibility != View.GONE) parent.addView(child) }

    private fun bar(context: Context, text: String?, palette: ControlPalette): TextView =
        label(context, text, 14f, palette.onWarning, bold = true).apply {
            gravity = Gravity.CENTER; setBackgroundColor(palette.warning); setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(10))
        }

    /** The whole-screen state: update required, blocked version, maintenance. */
    fun fullScreen(activity: Activity, state: ControlState, palette: ControlPalette, actions: ControlActions): View {
        val root = FrameLayout(activity).apply { setBackgroundColor(palette.surface) }
        val banner = if (state.isTestBuild) bar(activity, state.testBuildText, palette) else null
        val column = Column(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(activity.dp(28), activity.dp(24), activity.dp(28), activity.dp(24))
        }
        val title = label(activity, state.title, 26f, palette.text, bold = true, heading = true)
        val body = label(activity, state.body, 16f, palette.secondary).apply { setPadding(0, activity.dp(12), 0, activity.dp(28)) }
        val primary = state.buttonText?.let { button(activity, it, palette, true, actions.primary) }
        val secondary = state.secondaryButtonText?.takeIf { state.allowContinue }?.let { button(activity, it, palette, false, actions.secondary) }
        stack(column, title, body)
        primary?.let { column.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        secondary?.let { column.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(8) }) }
        val scroll = ScrollView(activity).apply { isFillViewport = true }
        val holder = FrameLayout(activity)
        holder.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        scroll.addView(holder, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        banner?.let { content.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fitSystemBars(root, content)
        return root
    }

    /** The card at the bottom: announcement and what's new. A tap outside closes it. */
    fun card(activity: Activity, state: ControlState, palette: ControlPalette, actions: ControlActions): View {
        val root = FrameLayout(activity).apply { setBackgroundColor(palette.scrim); setOnClickListener { actions.outside() }; isClickable = true }
        val column = Column(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(activity.dp(24), activity.dp(24), activity.dp(24), activity.dp(24))
            background = GradientDrawable().apply { setColor(palette.surface); val r = activity.dp(24).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            isClickable = true
        }
        val title = label(activity, state.title, 20f, palette.text, bold = true, heading = true)
        val body = label(activity, state.body, 16f, palette.secondary).apply { setPadding(0, activity.dp(8), 0, activity.dp(20)) }
        val scroll = CappedScroll(activity, (activity.resources.displayMetrics.heightPixels * 0.5f).toInt()).apply { addView(body) }
        stack(column, title)
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        state.buttonText?.let { column.addView(button(activity, it, palette, true, actions.primary), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        fitSystemBars(root, column, top = false)
        return root
    }

    /** A scroll view that never grows past a height, so a long text scrolls inside the card instead of pushing the button away. */
    private class CappedScroll(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
    }

    /** The heads-up bar for a maintenance window that starts soon. Drawn over the app's own screen. */
    fun headsUp(context: Context, state: ControlState, palette: ControlPalette, onTap: () -> Unit): TextView =
        bar(context, state.body, palette).apply { setOnClickListener { onTap() }; contentDescription = state.body }

    /** Pads a view by the system bars and the display cutout, so nothing sits under them (edge to edge, every API level). */
    @Suppress("DEPRECATION")
    @SuppressLint("NewApi")
    fun fitSystemBars(root: View, target: View?, top: Boolean = true) {
        val padded = target ?: root
        val base = intArrayOf(padded.paddingLeft, padded.paddingTop, padded.paddingRight, padded.paddingBottom)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val box = insetBox(insets)
            padded.setPadding(base[0] + box[0], base[1] + (if (top) box[1] else 0), base[2] + box[2], base[3] + box[3]); insets
        }
        root.requestApplyInsets()
    }

    @Suppress("DEPRECATION")
    private fun insetBox(insets: WindowInsets): IntArray = if (Build.VERSION.SDK_INT >= 30) {
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        intArrayOf(bars.left, bars.top, bars.right, bars.bottom)
    } else intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
}
