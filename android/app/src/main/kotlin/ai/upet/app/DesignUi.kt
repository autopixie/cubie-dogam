package ai.upet.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.text.LineBreakConfig
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One place for type, spacing, panels and every clickable control's press response. */
internal class DesignUi(val context: Context) {
    companion object {
        const val DISPLAY = 56f
        const val TITLE = 44f
        const val BODY = 32f
        const val LABEL = 24f
        const val SMALL = 22f
        const val SPEECH_TITLE = 40f
        const val SPEECH_SECONDARY = 30f
    }

    val dark: Boolean get() = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    fun color(id: Int): Int = context.getColor(id)
    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun fill(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    fun block(gap: Int = 24) = fill().apply { topMargin = dp(gap) }
    fun weighted(gap: Int = 0) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(gap) }
    fun scroll(child: View) = ScrollView(context).apply {
        isFillViewport = true; clipToPadding = false
        addView(child, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun label(value: String, size: Float = BODY, weight: Int = 400, tone: Int = R.color.upet_text_1) = TextView(context).apply {
        text = value; textSize = size
        typeface = context.resources.getFont(when (weight) { 700 -> R.font.asta_sans_bold; 600 -> R.font.asta_sans_semibold; else -> R.font.asta_sans })
        setTextColor(color(tone)); maxLines = 3; ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(dp(4).toFloat(), 1f)
        isAccessibilityHeading = weight >= 600 && size >= TITLE
    }

    /** Full source copy stays visible; Android 13+ keeps Korean phrases together. */
    fun untruncatedLabel(value: String, size: Float = BODY, weight: Int = 400, tone: Int = R.color.upet_text_1) =
        label(value, size, weight, tone).apply {
            maxLines = Int.MAX_VALUE; ellipsize = null
            if (Build.VERSION.SDK_INT >= 33) lineBreakWordStyle = LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
        }

    /** The four primary screens use the approved flat panel, without changing legacy panels. */
    fun screenPanel() = column().apply {
        setPadding(dp(44), dp(40), dp(44), dp(40))
        background = shape(color(R.color.upet_surface_1), 28)
    }

    fun questionInput() = android.widget.EditText(context).apply {
        hint = "예: 핸들 열선은 어디서 켜?"
        minHeight = dp(88); minimumHeight = minHeight
        textSize = BODY
        typeface = context.resources.getFont(R.font.asta_sans)
        setTextColor(color(R.color.upet_text_1)); setHintTextColor(color(R.color.upet_text_2))
        isSaveEnabled = false; isSaveFromParentEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        filters = arrayOf(android.text.InputFilter.LengthFilter(500))
        setSingleLine(false); maxLines = 3
    }

    fun shape(fill: Int, radius: Int = 36, outlined: Boolean = true) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        if (outlined) setStroke(dp(1), color(R.color.upet_line))
    }

    fun panel(level: Int = 2, small: Boolean = false) = column().apply {
        setPadding(dp(40), dp(40), dp(40), dp(40))
        background = shape(color(R.color.upet_surface_1), if (small) 32 else 36)
        // Framework shadows use the platform's light source; no shadow bitmap is bundled.
        elevation = dp(when (level) { 1 -> 6; 3 -> 20; else -> 8 }).toFloat()
        outlineAmbientShadowColor = color(when (level) { 1 -> R.color.upet_shadow_1; 3 -> R.color.upet_shadow_3; else -> R.color.upet_shadow_2 })
        outlineSpotShadowColor = outlineAmbientShadowColor
    }

    fun button(title: String, primary: Boolean = false, enabled: Boolean = true, action: () -> Unit) = Button(context).apply {
        text = title; isAllCaps = false; setSingleLine(true); maxLines = 1
        textSize = if (primary) 34f else 28f
        typeface = context.resources.getFont(if (primary) R.font.asta_sans_bold else R.font.asta_sans_semibold)
        minHeight = dp(if (primary) 104 else 88); minimumHeight = minHeight
        minimumWidth = 0; minWidth = 0; includeFontPadding = false
        setPadding(dp(24), dp(12), dp(24), dp(12))
        val base = color(if (primary) R.color.upet_text_1 else R.color.upet_surface_2)
        setTextColor(color(if (primary) R.color.upet_on_primary else R.color.upet_text_1))
        background = shape(base, 24); backgroundTintList = null
        isEnabled = enabled; alpha = if (enabled) 1f else .45f
        setOnClickListener { action() }
        press(this, base, 24)
    }

    fun quietButton(title: String, enabled: Boolean = true, action: () -> Unit) =
        button(title, enabled = enabled, action = action).apply {
            setTextColor(color(R.color.upet_text_2))
            background = shape(Color.TRANSPARENT, 0, outlined = false)
            press(this, Color.TRANSPARENT, 0, outlined = false)
        }

    fun navigationButton(title: String, current: Boolean = false, enabled: Boolean = true, action: () -> Unit) =
        button(title, enabled = enabled, action = action).apply {
            val base = color(if (current) R.color.upet_surface_2 else R.color.upet_surface_1)
            setTextColor(color(if (current) R.color.upet_text_1 else R.color.upet_text_2))
            background = shape(base, 22)
            press(this, base, 22)
        }

    fun bindAction(view: View, base: Int, radius: Int = 24, enabled: Boolean = true, action: () -> Unit) {
        view.isEnabled = enabled; view.isClickable = enabled; view.isFocusable = enabled
        view.setOnClickListener { if (view.isEnabled) action() }
        press(view, base, radius)
    }

    /** Immediate background change; 95% scale completes in 100ms, including checkable controls. */
    // This observer always returns false; the widget owns touch/keyboard/accessibility clicks.
    // Calling performClick here would duplicate the framework click.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun press(view: View, base: Int, radius: Int = 24, outlined: Boolean = true) {
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        hsv[2] = if (dark) (hsv[2] + .09f).coerceAtMost(1f) else (hsv[2] - .07f).coerceAtLeast(0f)
        val pressed = Color.HSVToColor(Color.alpha(base), hsv)
        view.setOnTouchListener { target, event ->
            if (target.isEnabled) when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.animate().cancel()
                    target.background = shape(pressed, radius, outlined)
                    target.animate().scaleX(.95f).scaleY(.95f).setDuration(100L).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    target.animate().cancel()
                    target.background = shape(base, radius, outlined)
                    target.animate().scaleX(1f).scaleY(1f).setDuration(100L).start()
                }
            }
            false
        }
    }

    fun badge(text: String) = label(text, 26f, 600).apply {
        gravity = Gravity.CENTER; minHeight = dp(64); setPadding(dp(24), 0, dp(24), 0)
        background = shape(color(R.color.upet_surface_2), 24)
    }
}
