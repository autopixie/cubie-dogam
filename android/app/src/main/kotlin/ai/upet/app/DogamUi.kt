package ai.upet.app

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Read-only native renderer. Only the supplied actions may change routes, quiz answers or progress. */
internal class DogamUi(private val context: Context, private val ui: DesignUi = DesignUi(context)) {
    enum class Tab { ALL, CAR, QUIZ }
    data class Slot(val no: Int, val id: String = "", val kind: String = "car", val name: String = "",
        val typeLabel: String = "", val rarity: Int = 1, val found: Boolean = false, val reserved: Boolean = false,
        val riddle: String = "", val hint2: String = "", val metDate: String? = null, val seasonStamp: String? = null,
        val where: String = "", val how: String = "", val goodDay: String = "", val note: String = "",
        val sourceLine: String = "", val sourceUrl: String = "", val question: String = "", val choices: List<String> = emptyList(),
        val feature: String = "", val sourceSection: String = "") {
        fun presentation() = DogamPresentationPolicy.Card(no, feature, name, kind, found, reserved, where, how, sourceSection)
    }
    data class Model(val slots: List<Slot>, val foundCount: Int, val totalCount: Int, val growthStage: Int,
        val title: String = "큐비 도감", val warning: String? = null, val demo: Boolean = false,
        val interactive: Boolean = true, val hintCards: List<DogamPresentationPolicy.Card> = slots.map(Slot::presentation))
    data class Actions(val onCard: (Int) -> Unit = {}, val onHint: (Int) -> Unit = {},
        val onTab: (Tab) -> Unit = {}, val onHome: () -> Unit = {}, val onFlip: () -> Unit = {},
        val onAnswer: (Int, Int) -> Unit = { _, _ -> })

    private val animations = mutableListOf<Animator>()
    private var animationGeneration = 0L
    private var lastDiscoveryKey: String? = null
    private fun dp(value: Int) = ui.dp(value)
    private fun color(id: Int) = context.getColor(id)
    private fun text(value: String, size: Float = 32f, weight: Int = 400, tone: Int = R.color.dogam_text) =
        ui.untruncatedLabel(value, size, weight, tone).apply { includeFontPadding = false }
    private fun fill(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(-1, height)
    private fun block(top: Int = 0) = fill().apply { topMargin = dp(top) }
    private fun shape(fill: Int, radius: Int = 4, stroke: Int? = null, thickness: Int = 2) = GradientDrawable().apply {
        setColor(color(fill)); cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(thickness), color(it)) }
    }
    private fun line() = View(context).apply { setBackgroundColor(color(R.color.dogam_separator)) }
    private fun quiet(title: String, enabled: Boolean = true, action: () -> Unit) = ui.quietButton(title, enabled, action).apply {
        setTextColor(color(R.color.dogam_blue)); gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 0); minHeight = dp(48); minimumHeight = dp(48)
    }

    private fun adaptive(build: (Int, Int) -> View): View = object : FrameLayout(context) {
        private var contentWidth = -1
        private var contentHeight = -1

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = View.MeasureSpec.getSize(widthMeasureSpec)
            val h = View.MeasureSpec.getSize(heightMeasureSpec)
            val bounded = View.MeasureSpec.getMode(widthMeasureSpec) != View.MeasureSpec.UNSPECIFIED &&
                View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.UNSPECIFIED
            if (bounded && w > 0 && h > 0) {
                val density = resources.displayMetrics.density.takeIf { it.isFinite() && it > 0f } ?: 1f
                val width = (w / density).toInt().coerceAtLeast(1)
                val height = (h / density).toInt().coerceAtLeast(1)
                if (contentWidth != width || contentHeight != height) {
                    contentWidth = width; contentHeight = height
                    val canvasWidth = width.coerceAtLeast(320)
                    val child = build(canvasWidth, height)
                    removeAllViews()
                    if (width < 320) {
                        val horizontal = HorizontalScrollView(context).apply {
                            isFillViewport = false
                            addView(child, ViewGroup.LayoutParams(dp(canvasWidth), -1))
                        }
                        addView(horizontal, LayoutParams(-1, -1))
                    } else addView(child, LayoutParams(-1, -1))
                }
            }
            // Children must exist before their measure pass, never be inserted from onSizeChanged.
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            diagnostic {
                val child = getChildAt(0)
                Log.i("PetApp", "dogamAdaptive root=${measuredWidth}x${measuredHeight} children=$childCount" +
                    " child=${child?.measuredWidth}x${child?.measuredHeight}")
            }
        }
        init { setBackgroundColor(color(R.color.dogam_bg)) }
    }

    private fun top(model: Model, horizontal: Boolean): View = ui.column().apply {
        if (horizontal) {
            val row = ui.row().apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(56), 0, dp(56), 0)
                addView(text(model.title, 44f, 700), LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.column().apply {
                    gravity = Gravity.END
                    addView(text("${model.foundCount} / ${model.totalCount}", 40f, 700))
                    addView(text("이 차에서 만날 수 있는 친구 기준", 22f, tone = R.color.dogam_muted), block(4))
                })
            }
            addView(row, fill(dp(127)))
        } else {
            val column = ui.column().apply {
                setPadding(dp(24), dp(16), dp(24), dp(16))
                addView(text(model.title, 44f, 700), fill())
                addView(text("${model.foundCount} / ${model.totalCount}", 40f, 700), block(12))
                addView(text("이 차에서 만날 수 있는 친구 기준", 22f, tone = R.color.dogam_muted), block(4))
            }
            addView(column, fill())
        }
        addView(line(), fill(dp(1)))
    }

    fun binder(model: Model, actions: Actions, tab: Tab = Tab.ALL, riddleNo: Int? = null,
        hintVisible: Boolean = false): View {
        cancelAnimations()
        return adaptive { width, height -> binderContent(model, actions, tab, riddleNo, hintVisible, width, height) }
    }

    private fun binderContent(model: Model, actions: Actions, tab: Tab, riddleNo: Int?, hintVisible: Boolean,
        width: Int, height: Int): View {
        val layout = DogamLayoutPolicy.binder(width, height)
        val root = ui.column()
        val horizontalHeader = DogamLayoutPolicy.horizontalHeader(width, context.resources.configuration.fontScale)
        root.addView(top(model, horizontalHeader), fill(if (horizontalHeader) dp(128) else ViewGroup.LayoutParams.WRAP_CONTENT))
        val today = DogamPresentationPolicy.today(model.slots.map(Slot::presentation))
        val riddle = model.slots.firstOrNull { it.no == today }
        val left = leftColumn(model, riddle, hintVisible && (riddleNo == null || riddleNo == riddle?.no), actions, layout.sideBySide)
        val right = ui.column().apply { setPadding(dp(layout.padding), dp(32), dp(layout.padding), dp(32)) }
        val tabs = ui.row().apply {
            for ((target, label) in listOf(Tab.ALL to "전체 ${model.slots.size}",
                Tab.CAR to "차가 확인 ${model.slots.count { !it.reserved && it.kind == "car" }}",
                Tab.QUIZ to "설명서 퀴즈 ${model.slots.count { !it.reserved && it.kind == "quiz" }}")) {
                val item = ui.column().apply {
                    val value = text(label, 28f, if (target == tab) 700 else 400,
                        if (target == tab) R.color.dogam_text else R.color.dogam_secondary)
                    addView(value)
                    if (target == tab) addView(View(context).apply { setBackgroundColor(color(R.color.dogam_text)) }, fill(dp(3)).apply { topMargin = dp(6) })
                }
                ui.bindAction(item, Color.TRANSPARENT, 0, model.interactive) { actions.onTab(target) }
                addView(item, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(if (width >= 1300) 36 else 20) })
            }
        }
        val tabScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }
        right.addView(tabScroll, fill(dp(44)))
        val slots = model.slots.filter { tab == Tab.ALL || (tab == Tab.CAR && it.kind == "car") || (tab == Tab.QUIZ && it.kind == "quiz") }
        // Every row reserves the native measured label height, including large system text.
        val labelWidth = dp((layout.cellWidth - 20).coerceAtLeast(1))
        val cellHeight = maxOf(dp(layout.cellHeight), slots.filterNot { it.reserved }.maxOfOrNull { slot ->
            val label = cellLabel(slot)
            label.measure(View.MeasureSpec.makeMeasureSpec(labelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            dp(42 + 140 + 8 + 26) + label.measuredHeight
        } ?: 0)
        val grid = GridView(context).apply {
            numColumns = layout.columns; columnWidth = dp(layout.cellWidth)
            horizontalSpacing = dp(layout.gap); verticalSpacing = dp(layout.gap)
            stretchMode = GridView.NO_STRETCH; gravity = Gravity.START
            clipToPadding = false; isVerticalScrollBarEnabled = true
            val cards = object : BaseAdapter() {
                override fun getCount() = slots.size
                override fun getItem(position: Int) = slots[position]
                override fun getItemId(position: Int) = slots[position].no.toLong()
                override fun hasStableIds() = true
                override fun areAllItemsEnabled() = model.interactive && slots.none { it.reserved }
                override fun isEnabled(position: Int) = model.interactive && slots.getOrNull(position)?.reserved == false
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
                    cell(slots[position], riddle?.no, layout.cellWidth, cellHeight, model.interactive, convertView as? FrameLayout)
            }
            adapter = cards
            setDrawSelectorOnTop(true)
            bindGridInput(this, slots, cards, model.interactive, actions)
        }
        right.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(26) })
        if (model.demo) right.addView(text("시연용", 22f, tone = R.color.dogam_muted), block(8))
        if (layout.sideBySide) {
            val row = ui.row().apply { gravity = Gravity.TOP }
            row.addView(ui.scroll(left), LinearLayout.LayoutParams(dp(layout.sideWidth), -1))
            row.addView(line(), LinearLayout.LayoutParams(dp(1), -1))
            row.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
            root.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val intro = ui.scroll(left)
            root.addView(intro, fill(dp(if (height >= 900) 360 else 240)))
            root.addView(line(), fill(dp(1)))
            root.addView(right, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return root
    }

    private fun leftColumn(model: Model, slot: Slot?, hintVisible: Boolean, actions: Actions, sideBySide: Boolean): LinearLayout = ui.column().apply {
        setPadding(dp(if (sideBySide) 48 else 28), dp(if (sideBySide) 40 else 16), dp(if (sideBySide) 48 else 28), dp(28))
        val worried = !model.warning.isNullOrBlank()
        val pet = PetView(context, if (worried) PetFace.WORRIED else PetFace.SMILE, model.growthStage)
        addView(pet, LinearLayout.LayoutParams(dp(if (sideBySide) 300 else 160), dp(if (sideBySide) 300 else 160)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        if (worried) addView(text(requireNotNull(model.warning), 28f, tone = R.color.dogam_warning), block(16))
        if (slot != null) {
            val hint = DogamPresentationPolicy.hint(slot.hint2, model.hintCards)
            addView(text("오늘의 수수께끼", 24f, 600, R.color.dogam_secondary), block(28))
            val box = ui.column().apply {
                setPadding(dp(28), dp(28), dp(28), dp(28)); background = shape(R.color.dogam_cell)
                addView(text(number(slot.no), 24f, tone = R.color.dogam_muted))
                addView(text(slot.riddle), block(10))
                if (hintVisible && slot.hint2.isNotBlank()) addView(text(hint ?: "이 힌트는 아직 보여 줄 수 없어요.", 28f, tone = R.color.dogam_secondary), block(16))
                if (!hintVisible && slot.hint2.isNotBlank()) addView(quiet("힌트 더 보기", model.interactive) { actions.onHint(slot.no) }, block(22))
                if (slot.kind == "quiz") addView(quiet("퀴즈 풀기", model.interactive) { actions.onCard(slot.no) }, block(8))
            }
            addView(box, block(14))
        }
    }

    private fun cellLabel(slot: Slot): TextView {
        val visible = DogamPresentationPolicy.cell(slot.presentation())
        return text(visible.label, 26f, 600, if (slot.found) R.color.dogam_text else R.color.dogam_unknown).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun cell(slot: Slot, today: Int?, width: Int, heightPx: Int, interactive: Boolean, recycled: FrameLayout? = null): View = (recycled ?: FrameLayout(context)).apply {
        removeAllViews()
        layoutParams = AbsListView.LayoutParams(dp(width), heightPx)
        val visible = DogamPresentationPolicy.cell(slot.presentation())
        tag = "dogam_cell_${slot.no}"
        val base = if (slot.found) R.color.dogam_found else R.color.dogam_cell
        val stroke = when { slot.no == today -> R.color.dogam_blue; slot.found && slot.kind == "quiz" -> R.color.dogam_silver; slot.found -> R.color.dogam_card_line; else -> null }
        background = shape(base, stroke = stroke, thickness = if (slot.no == today) 3 else 2)
        addView(text(number(slot.no), 20f, tone = R.color.dogam_muted), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = dp(14); topMargin = dp(10) })
        if (slot.reserved) {
            addView(text("?", 56f, 700, R.color.dogam_card_line), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            contentDescription = number(slot.no) + " 곧 만날 친구"
        } else {
            val center = ui.column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(DogamArtView(context, slot.no, visible.silhouette), LinearLayout.LayoutParams(dp(140), dp(140)))
                addView(cellLabel(slot), block(8))
            }
            addView(center, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { leftMargin = dp(10); rightMargin = dp(10); topMargin = dp(42) })
            if (slot.found) {
                if (slot.seasonStamp != null) addView(text("겨울", 18f, tone = R.color.dogam_winter).apply {
                    setPadding(dp(6), dp(1), dp(6), dp(1)); background = shape(base, 2, R.color.dogam_winter, 1)
                }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { rightMargin = dp(10); topMargin = dp(8) })
                addView(text("★".repeat(slot.rarity.coerceIn(1, 5)), 18f, tone = R.color.dogam_secondary),
                    FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(14); bottomMargin = dp(10) })
            }
            contentDescription = visible.accessibilityLabel
        }
        clearGridCellInput(this, interactive && !slot.reserved)
    }

    /** AdapterView owns touch, keyboard and accessibility item activation exactly once. */
    private fun bindGridInput(grid: GridView, slots: List<Slot>, cards: BaseAdapter,
        interactive: Boolean, actions: Actions) {
        grid.isEnabled = interactive
        grid.setOnItemClickListener { parent, child, position, itemId ->
            if (!interactive || !grid.isEnabled || !grid.isAttachedToWindow || !grid.isShown || !grid.hasWindowFocus()) return@setOnItemClickListener
            val slot = slots.getOrNull(position) ?: return@setOnItemClickListener
            if (parent !== grid || parent.adapter !== cards || slot.reserved || !cards.isEnabled(position) ||
                itemId != slot.no.toLong() || cards.getItemId(position) != itemId || cards.getItem(position) !== slot) return@setOnItemClickListener
            if (child != null && child.tag != "dogam_cell_${slot.no}") return@setOnItemClickListener
            // Activity.act performs the final live safety/session check.
            actions.onCard(slot.no)
        }
    }

    private fun clearGridCellInput(cell: ViewGroup, enabled: Boolean) {
        // setOnClickListener(null) may itself make a View clickable; reset flags afterwards.
        cell.setOnClickListener(null)
        cell.setOnTouchListener(null)
        cell.isClickable = false
        cell.isFocusable = false
        cell.isFocusableInTouchMode = false
        cell.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        cell.isEnabled = enabled
    }

    /** An unfound car exposes instructions only, never the collected-card front or back. */
    fun guide(model: Model, slot: Slot, actions: Actions): View {
        val guide = DogamPresentationPolicy.guide(slot.presentation()) ?: return binder(model, actions)
        cancelAnimations()
        return adaptive { width, height ->
            val root = ui.column()
            root.addView(backRow(actions, model.interactive), fill(dp(100)))
            val wide = width >= 1300 && width >= height
            val summary = ui.column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(28), dp(28), dp(28), dp(28))
                background = shape(R.color.dogam_cell, 6)
                addView(text(number(slot.no), 24f, tone = R.color.dogam_muted))
                addView(DogamArtView(context, slot.no, true), LinearLayout.LayoutParams(dp(260), dp(260)).apply { topMargin = dp(20) })
                addView(text(guide.feature, 44f, 700).apply { gravity = Gravity.CENTER }, block(20))
            }
            val instructions = ui.column().apply {
                for (field in guide.fields) {
                    addView(text(field.label, 24f, 600, R.color.dogam_secondary), block(26))
                    addView(text(field.value), block(8))
                    addView(line(), fill(dp(1)).apply { topMargin = dp(26) })
                }
                addView(text("출처 · ${guide.sourceSection}", 22f, tone = R.color.dogam_muted), block(22))
            }
            if (wide) {
                val row = ui.row().apply { gravity = Gravity.TOP; setPadding(dp(80), dp(60), dp(80), dp(40)) }
                row.addView(ui.scroll(summary), LinearLayout.LayoutParams(dp(460), -1))
                row.addView(ui.scroll(instructions), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(80) })
                root.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
            } else {
                val column = ui.column().apply { setPadding(dp(28), dp(20), dp(28), dp(28)) }
                column.addView(summary, fill())
                column.addView(instructions, block(28))
                root.addView(ui.scroll(column), LinearLayout.LayoutParams(-1, 0, 1f))
            }
            root
        }
    }

    fun detail(model: Model, slot: Slot, actions: Actions, backFirst: Boolean = false): View {
        if (!slot.found) return when (DogamPresentationPolicy.destination(slot.presentation())) {
            DogamPresentationPolicy.Destination.QUIZ -> quiz(model, slot, actions)
            DogamPresentationPolicy.Destination.GUIDE -> guide(model, slot, actions)
            else -> binder(model, actions)
        }
        cancelAnimations()
        return adaptive { width, height ->
            val root = ui.column()
            root.addView(backRow(actions, model.interactive), fill(dp(100)))
            val wide = width >= 1300 && width >= height
            val front = front(slot, if (wide) 420 else 260)
            val back = back(slot)
            if (wide) {
                val row = ui.row().apply { gravity = Gravity.TOP; setPadding(dp(80), 0, dp(80), 0) }
                row.addView(ui.scroll(if (backFirst) back else front), LinearLayout.LayoutParams(dp(620), -1))
                row.addView(ui.scroll(if (backFirst) front else back), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(80) })
                root.addView(row, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(80) })
            } else {
                val column = ui.column().apply { setPadding(dp(28), dp(20), dp(28), dp(20)) }
                column.addView(if (backFirst) back else front, fill())
                column.addView(if (backFirst) front else back, block(28))
                root.addView(ui.scroll(column), LinearLayout.LayoutParams(-1, 0, 1f))
            }
            val compactFooter = width < 600
            val footer = FrameLayout(context)
            footer.addView(quiet("뒤집기", model.interactive, actions.onFlip), FrameLayout.LayoutParams(dp(180), dp(if (compactFooter) 48 else 80), (if (compactFooter) Gravity.TOP else Gravity.BOTTOM) or Gravity.START).apply {
                leftMargin = dp(if (wide) 80 else 28)
                if (compactFooter) topMargin = dp(8) else bottomMargin = dp(if (wide) 70 else 20)
            })
            footer.addView(text("도감으로", 30f, 700, R.color.dogam_bg).apply {
                tag = "dogam_primary_action"; gravity = Gravity.CENTER
                background = shape(R.color.dogam_text); setPadding(dp(44), dp(16), dp(44), dp(16))
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info); info.className = "android.widget.Button"
                    }
                }
                ui.bindAction(this, color(R.color.dogam_text), 4, model.interactive, actions.onHome)
            }, FrameLayout.LayoutParams(if (compactFooter) -1 else -2, dp(80), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(if (wide) 80 else 28)
                if (compactFooter) leftMargin = dp(28)
                bottomMargin = dp(if (wide) 70 else 20)
            })
            root.addView(footer, fill(dp(if (wide) 160 else if (compactFooter) 180 else 120)))
            root
        }
    }

    private fun backRow(actions: Actions, enabled: Boolean): View = ui.row().apply {
        setPadding(dp(56), 0, dp(56), 0)
        addView(quiet("← 도감", enabled, actions.onHome))
    }

    private fun front(slot: Slot, artSize: Int): View = FrameLayout(context).apply {
        minimumHeight = dp(if (artSize >= 420) 860 else 550)
        background = shape(R.color.dogam_found, 6, if (slot.kind == "quiz") R.color.dogam_silver else R.color.dogam_card_line)
        val content = ui.column().apply {
            gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(32), dp(60), dp(32), dp(40))
            addView(DogamArtView(context, slot.no, !slot.found), LinearLayout.LayoutParams(dp(artSize), dp(artSize)))
            addView(text(slot.name, 56f, 700).apply { gravity = Gravity.CENTER }, block(24))
            val stars = "★".repeat(slot.rarity.coerceIn(1, 5))
            addView(text("${number(slot.no)} · ${slot.typeLabel} · $stars" + if (slot.seasonStamp != null) " 겨울" else "", 26f,
                tone = R.color.dogam_secondary).apply { gravity = Gravity.CENTER }, block(14))
            if (slot.found) addView(text(DogamLayoutPolicy.dateLine(slot.metDate), 26f,
                tone = R.color.dogam_secondary).apply { gravity = Gravity.CENTER }, block(10))
        }
        addView(content, FrameLayout.LayoutParams(-1, -2))
        if (slot.seasonStamp != null) addView(text("겨울 도장", 24f, tone = R.color.dogam_winter).apply {
            setPadding(dp(6), dp(2), dp(6), dp(2)); background = shape(R.color.dogam_found, 2, R.color.dogam_winter, 1)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { rightMargin = dp(24); topMargin = dp(22) })
    }

    private fun back(slot: Slot): View = ui.column().apply {
        for ((label, value) in listOf("어디 사나요" to slot.where, "깨우는 법" to slot.how,
            "좋아하는 날" to slot.goodDay, "주의" to slot.note)) {
            val field = ui.column().apply {
                setPadding(0, dp(26), 0, dp(26))
                addView(text(label, 24f, 600, R.color.dogam_secondary))
                addView(text(value), block(8))
            }
            addView(field); addView(line(), fill(dp(1)))
        }
        addView(text("출처 · ${slot.sourceLine}", 22f, tone = R.color.dogam_muted), block(22))
        // The full URL remains readable without launching a browser or making a network call.
        if (slot.sourceUrl.isNotBlank()) addView(text(slot.sourceUrl, 22f, tone = R.color.dogam_muted), block(8))
    }

    fun quiz(model: Model, slot: Slot, actions: Actions, wrong: Boolean = false): View {
        cancelAnimations()
        return adaptive { width, height ->
            val root = ui.column()
            root.addView(backRow(actions, model.interactive), fill(dp(100)))
            val wide = width >= 1300 && width >= height
            val card = ui.column().apply {
                gravity = Gravity.CENTER; minimumHeight = dp(if (wide) 520 else 260)
                background = shape(R.color.dogam_cell, 6)
                addView(DogamArtView(context, slot.no, !slot.found), LinearLayout.LayoutParams(dp(260), dp(260)))
                addView(text("${number(slot.no)} · 설명서 퀴즈", 28f, tone = R.color.dogam_muted).apply { gravity = Gravity.CENTER }, block(18))
            }
            val question = ui.column().apply {
                addView(text(slot.question, 40f, 700))
                slot.choices.forEachIndexed { index, choice ->
                    val option = ui.column().apply {
                        setPadding(dp(34), dp(28), dp(34), dp(28)); background = shape(R.color.dogam_bg, 4, R.color.dogam_card_line)
                        addView(text(choice, 34f))
                    }
                    ui.bindAction(option, color(R.color.dogam_bg), 4, model.interactive) { actions.onAnswer(slot.no, index) }
                    addView(option, block(26))
                }
                addView(text(if (wrong) "다시 해 봐요" else "맞히면 카드가 생겨요. 틀려도 다시 해 볼 수 있어요.",
                    24f, tone = R.color.dogam_muted), block(28))
            }
            if (wide) {
                val row = ui.row().apply { gravity = Gravity.TOP; setPadding(dp(80), dp(100), dp(80), dp(40)) }
                row.addView(card, LinearLayout.LayoutParams(dp(400), dp(520)))
                row.addView(ui.scroll(question), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(80) })
                root.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
            } else {
                val body = ui.column().apply { setPadding(dp(28), dp(20), dp(28), dp(28)) }
                body.addView(card, fill()); body.addView(question, block(28))
                root.addView(ui.scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
            }
            root
        }
    }

    /** Root owns the 1.5-second screen lifetime; a renderer cannot grant a card or keep itself open. */
    fun discovery(model: Model, slot: Slot, announcement: String, evidence: String, reducedMotion: Boolean = false): View {
        cancelAnimations()
        val key = "${slot.no}:${slot.metDate}:${slot.seasonStamp}"
        val animate = key != lastDiscoveryKey && !reducedMotion && ValueAnimator.areAnimatorsEnabled()
        lastDiscoveryKey = key
        val generation = animationGeneration
        return adaptive { width, height ->
            val root = FrameLayout(context)
            root.addView(binderContent(model.copy(interactive = false), Actions(), Tab.ALL, null, false, width, height), FrameLayout.LayoutParams(-1, -1))
            root.addView(View(context).apply { setBackgroundColor(color(R.color.dogam_dim)); isClickable = true }, FrameLayout.LayoutParams(-1, -1))
            val wide = width >= 1300 && width >= height
            val popupWidth = if (wide) 560 else (width - 48).coerceAtLeast(260)
            val popup = ui.column().apply {
                gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(40), dp(if (wide) 70 else 28), dp(40), dp(32))
                background = shape(R.color.dogam_found, 6, R.color.dogam_silver)
                addView(DogamArtView(context, slot.no, false), LinearLayout.LayoutParams(dp(if (wide) 360 else 220), dp(if (wide) 360 else 220)))
                addView(text(number(slot.no), 28f, tone = R.color.dogam_muted), block(12))
                addView(text(announcement, if (wide) 52f else 36f, 700).apply { gravity = Gravity.CENTER }, block(6))
                addView(text(evidence, 26f, tone = R.color.dogam_secondary).apply { gravity = Gravity.CENTER }, block(18))
                if (slot.seasonStamp != null) addView(text("겨울 도장", 24f, tone = R.color.dogam_winter), block(16))
            }
            val popupScroll = ui.scroll(popup)
            val popupHeight = if (wide) 760 else (height - 200).coerceAtLeast(300)
            root.addView(popupScroll, FrameLayout.LayoutParams(dp(popupWidth), dp(popupHeight), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(if (wide) 150 else 40) })
            val pet = PetView(context, if (model.warning.isNullOrBlank()) PetFace.JOY else PetFace.WORRIED, model.growthStage)
            root.addView(pet, FrameLayout.LayoutParams(dp(if (wide) 260 else 150), dp(if (wide) 260 else 150), Gravity.TOP or Gravity.END).apply {
                rightMargin = dp(if (wide) (width - 1380).coerceAtLeast(16) else 16)
                topMargin = dp(if (wide) 620 else (height - 190).coerceAtLeast(80))
            })
            if (animate) popupScroll.post {
                if (generation == animationGeneration && popupScroll.isAttachedToWindow) {
                    val shakeTarget = root.findViewWithTag<View>("dogam_cell_${slot.no}") ?: popupScroll
                    val shake = ObjectAnimator.ofFloat(shakeTarget, View.ROTATION, -2f, 2f, -2f, 0f).apply { duration = 220L }
                    val flip = ObjectAnimator.ofFloat(popupScroll, View.ROTATION_Y, 90f, 0f).apply { duration = 260L }
                    val sequence = AnimatorSet().apply { playSequentially(shake, flip) }
                    animations.add(sequence); sequence.start()
                }
            }
            root
        }
    }

    fun cancelAnimations() {
        animationGeneration++
        animations.forEach(Animator::cancel); animations.clear()
    }

    private fun number(no: Int) = "No." + no.toString().padStart(3, '0')
}
