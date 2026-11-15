package ai.upet.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout

/** A measured, wrapping speech panel and tail, next to the approved artwork. No overlay. */
internal class PetSpeechView(
    context: Context,
    private val ui: DesignUi,
    pictureContainer: View,
    private val reactionPicture: View,
    pictureParams: LayoutParams,
    private val allowed: () -> Boolean,
    private val report: (String) -> Unit,
) : LinearLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    private val bubble = ui.column().apply {
        setPadding(ui.dp(34), ui.dp(26), ui.dp(34), ui.dp(28))
        background = ui.shape(ui.color(R.color.upet_surface_2), 28)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val tail = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.color(R.color.upet_surface_2) }
        private val path = Path()
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            path.reset()
            path.moveTo(0f, 0f); path.lineTo(w.toFloat(), 0f)
            path.lineTo(w / 2f, h.toFloat()); path.close()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawPath(path, paint)
        }
    }
    private val scales = arrayOf(Settings.Global.ANIMATOR_DURATION_SCALE,
        Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.WINDOW_ANIMATION_SCALE)
    private var constructed = false
    private var observationFailed = false
    private var registered = false
    private var animator: AnimatorSet? = null
    private var ticket = PetReactionTicket(PetReactionKind.NONE)
    private var replacement = false
    private var visibleSpeech = false
    private var contentDrawn = false
    private var contentVersion = 0L
    private var onShown: (Long) -> Unit = {}
    var motionChanged: () -> Unit = {}
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { if (reduced()) cancelReaction("reduced_motion") }
    }
    private val exploration = AccessibilityManager.TouchExplorationStateChangeListener {
        if (reduced()) cancelReaction("reduced_motion")
    }
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        addView(bubble, LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(tail, LayoutParams(ui.dp(32), ui.dp(16)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(pictureContainer, pictureParams.apply { topMargin += ui.dp(14) })
        constructed = true
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val available = if (mode == MeasureSpec.UNSPECIFIED) ui.dp(PetPresentation.MAX_SPEECH_WIDTH_DP)
            else MeasureSpec.getSize(widthMeasureSpec)
        val capped = minOf(ui.dp(PetPresentation.MAX_SPEECH_WIDTH_DP), available)
        super.onMeasure(MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST), heightMeasureSpec)
    }
    fun preserveForReplacement() { replacement = true }
    fun show(speech: PetSpeech, reaction: PetReactionTicket, shown: (Long) -> Unit = {}) {
        contentVersion++; contentDrawn = false
        onShown = shown
        cancelReaction("content_changed", false)
        ticket = reaction
        visibleSpeech = speech.visible
        bubble.visibility = if (speech.visible) VISIBLE else GONE
        tail.visibility = bubble.visibility
        bubble.removeAllViews()
        speech.lines.forEachIndexed { index, line ->
            val text = if (index == 0) ui.untruncatedLabel(line, DesignUi.SPEECH_TITLE, 700)
                else ui.untruncatedLabel(line, DesignUi.SPEECH_SECONDARY, tone = R.color.upet_text_2)
            bubble.addView(text.apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                setLineSpacing(0f, if (index == 0) 1.25f else 1.35f)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
                accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            }, ui.fill().apply { if (index > 0) topMargin = ui.dp(10) })
        }
        post { tryStart() }
    }
    fun syncSafety() {
        if (!allowed()) {
            bubble.visibility = GONE; tail.visibility = GONE
            cancelReaction("restricted_or_background")
        }
    }
    fun cancelReaction(reason: String, abandon: Boolean = true) {
        val active = animator != null
        if (abandon && !replacement) ticket.cancel()
        if (!abandon || replacement) animator?.let { ticket.pauseAt(it.currentPlayTime) }
        animator?.cancel(); animator = null
        reactionPicture.translationY = 0f
        bubble.alpha = 1f; bubble.scaleX = 1f; bubble.scaleY = 1f
        if (active) diagnostic { report("cancel reason=" + reason) }
        motionChanged()
    }
    private fun reduced(): Boolean = try {
        observationFailed || !ValueAnimator.areAnimatorsEnabled() ||
            scales.any { Settings.Global.getFloat(context.contentResolver, it, 1f) <= 0f } ||
            accessibility?.isTouchExplorationEnabled == true
    } catch (_: Exception) { true }
    private fun tryStart() {
        if (animator != null || !contentDrawn) return
        if (!isAttachedToWindow || !isShown || windowVisibility != VISIBLE || !hasWindowFocus()) return
        if (visibleSpeech) onShown(SystemClock.uptimeMillis())
        if (!allowed() || !visibleSpeech || reduced()) { cancelReaction("environment"); return }
        val elapsed = ticket.playhead(SystemClock.uptimeMillis()) ?: return
        val kind = ticket.kind
        motionChanged()
        bubble.pivotX = bubble.width / 2f; bubble.pivotY = bubble.height.toFloat()
        val fade = ObjectAnimator.ofFloat(bubble, View.ALPHA, 0f, 1f).apply { duration = 200L }
        val growX = ObjectAnimator.ofFloat(bubble, View.SCALE_X, .96f, 1f).apply { duration = 200L }
        val growY = ObjectAnimator.ofFloat(bubble, View.SCALE_Y, .96f, 1f).apply { duration = 200L }
        val dy = if (kind == PetReactionKind.BOUNCE) floatArrayOf(0f, -ui.dp(12).toFloat(), 0f, -ui.dp(12).toFloat(), 0f)
            else floatArrayOf(0f, -ui.dp(4).toFloat(), 0f)
        val motion = ObjectAnimator.ofFloat(reactionPicture, View.TRANSLATION_Y, *dy).apply {
            duration = if (kind == PetReactionKind.BOUNCE) 800L else 300L
        }
        animator = AnimatorSet().apply {
            playTogether(fade, growX, growY, motion)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    reactionPicture.translationY = 0f
                    bubble.alpha = 1f; bubble.scaleX = 1f; bubble.scaleY = 1f
                    if (!replacement) ticket.finish()
                    animator = null
                    motionChanged()
                }
            })
            start()
            if (elapsed > 0) currentPlayTime = elapsed
        }
        diagnostic { report((if (elapsed == 0L) "start" else "continue offsetMs=" + elapsed) + " kind=" + kind + " durationMs=" + if (kind == PetReactionKind.BOUNCE) "800" else "300") }
    }
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!contentDrawn) {
            contentDrawn = true
            val version = contentVersion
            // A queued attach/focus callback alone is not proof the new content was drawn.
            post { if (version == contentVersion) tryStart() }
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observationFailed = false
        try {
            scales.forEach { context.contentResolver.registerContentObserver(Settings.Global.getUriFor(it), false, observer) }
            registered = accessibility?.addTouchExplorationStateChangeListener(exploration) == true
        } catch (_: Exception) { observationFailed = true }
        post { tryStart() }
    }
    override fun onDetachedFromWindow() {
        contentVersion++; contentDrawn = false
        cancelReaction("detached", false)
        try { context.contentResolver.unregisterContentObserver(observer) } catch (_: Exception) { }
        if (registered) accessibility?.removeTouchExplorationStateChangeListener(exploration)
        registered = false
        super.onDetachedFromWindow()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!constructed) return
        if (!hasWindowFocus) cancelReaction("window_focus") else tryStart()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (constructed && visibility != VISIBLE) cancelReaction("window_hidden")
    }
}
