package ai.kimi.code.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * DSH-Go 风格的原生界面层。
 *
 * 参考的是 DSH Go 0.2.x 的原生版（不是网页版）：淡紫灰底、半透明白卡、
 * 大圆角、细线图标、状态圆点。这里只做「外壳」——会话列表、抽屉、设置、
 * 运行页；聊天区仍然交给官方 dist-web，功能一个不少。
 *
 * 和 EtaUi 的关系：EtaUi 是早前 Eta 风格的工厂（白底蓝强调），这里的
 * 色板与字号按 DSH 重排，两者并存，新界面只用这一套。
 */
object DshUi {

    // ---- palette -----------------------------------------------------------
    const val BG_TOP = "#F8F7FC"
    const val BG_BOTTOM = "#EEEEF7"
    const val CARD = "#FFFFFF"
    const val CARD_SOFT = "#F7F7FB"
    const val TEXT = "#14141B"
    const val SUB = "#6E6E7A"
    const val MUTED = "#9A9AA6"
    const val LINE = "#E9E9F1"
    const val PRIMARY = "#4F5BD5"
    const val PRIMARY_DEEP = "#3F49B8"
    const val PRIMARY_SOFT = "#ECEDFB"
    const val GREEN = "#2FBF71"
    const val AMBER = "#E7A23B"
    const val RED = "#E5544B"
    const val GLASS_STROKE = "#F0F0F7"

    fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
    fun Context.sp(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics,
    )

    // ---- surfaces ----------------------------------------------------------

    /** The page background: a very soft vertical lilac wash. */
    fun background(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.parseColor(BG_TOP), Color.parseColor(BG_BOTTOM)),
    )

    fun fill(color: String, radiusDp: Float, stroke: String? = null, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = radiusDp * android.content.res.Resources.getSystem().displayMetrics.density
            if (stroke != null) {
                setStroke(
                    maxOf(1, (strokeDp * android.content.res.Resources.getSystem().displayMetrics.density).toInt()),
                    Color.parseColor(stroke),
                )
            }
        }

    fun fillInt(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * android.content.res.Resources.getSystem().displayMetrics.density
    }

    /** A card with the app's soft elevation and a hairline rim. */
    fun card(context: Context, radius: Float = 20f): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = fill(CARD, radius, GLASS_STROKE)
        elevation = context.dp(1.5f).toFloat()
        clipToOutline = true
    }

    /** White card that holds settings rows, no inner padding. */
    fun group(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = fill(CARD, 20f, GLASS_STROKE)
        elevation = context.dp(1f).toFloat()
        clipToOutline = true
    }

    fun vp(context: Context, vararg children: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        children.forEach { addView(it) }
    }

    fun hp(context: Context, vararg children: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        children.forEach { addView(it) }
    }

    fun spacer(context: Context, heightDp: Float): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, context.dp(heightDp))
    }

    fun gap(context: Context, widthDp: Float): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(context.dp(widthDp), 1)
    }

    // ---- text --------------------------------------------------------------

    fun text(
        context: Context,
        value: String,
        sizeSp: Float = 15f,
        color: String = TEXT,
        bold: Boolean = false,
        lines: Int = 1,
    ): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(Color.parseColor(color))
        if (bold) setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        maxLines = lines
        if (lines == 1) ellipsize = android.text.TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    fun sectionLabel(context: Context, value: String): TextView = text(context, value, 12.5f, MUTED, true).apply {
        letterSpacing = 0.02f
        setPadding(context.dp(6f), context.dp(18f), 0, context.dp(8f))
    }

    // ---- buttons -----------------------------------------------------------

    /**
     * Round icon key. `solid` paints the circle in [tint], otherwise it is a
     * white glass disc with a hairline rim (the DSH top bar's look).
     */
    fun circleButton(
        context: Context,
        icon: String,
        sizeDp: Float = 40f,
        solid: String? = null,
        tint: String = TEXT,
        iconSizeDp: Int = 20,
        onClick: () -> Unit,
    ): FrameLayout = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(context.dp(sizeDp), context.dp(sizeDp))
        background = RippleDrawable(
            ColorStateList.valueOf(Color.parseColor("#204F5BD5")),
            fill(solid ?: "#FFFFFF", sizeDp / 2f, if (solid == null) "#EDEDF5" else null),
            null,
        )
        elevation = context.dp(1f).toFloat()
        val glyph = DshIcon.view(
            context,
            icon,
            if (solid != null) Color.WHITE else Color.parseColor(tint),
            iconSizeDp,
            if (solid != null) 1.9f else 1.7f,
        )
        addView(glyph, DshUi.centerParams())
        setOnClickListener { onClick() }
        isClickable = true
    }

    fun pillButton(
        context: Context,
        label: String,
        solid: Boolean = true,
        icon: String? = null,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = RippleDrawable(
            ColorStateList.valueOf(Color.parseColor(if (solid) "#33FFFFFF" else "#1A4F5BD5")),
            fill(if (solid) PRIMARY else "#FFFFFF", 18f, if (solid) null else LINE),
            null,
        )
        setPadding(context.dp(if (icon != null) 12f else 16f), 0, context.dp(16f), 0)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(36f),
        )
        if (icon != null) {
            addView(DshIcon.view(context, icon, if (solid) Color.WHITE else Color.parseColor(PRIMARY), 18, 2f))
            addView(gap(context, 6f))
        }
        addView(text(context, label, 14f, if (solid) "#FFFFFF" else PRIMARY, true))
        setOnClickListener { onClick() }
        isClickable = true
    }

    // ---- list rows ---------------------------------------------------------

    /** One conversation row: title + subtitle + trailing marker. */
    fun sessionRow(
        context: Context,
        title: String,
        subtitle: String,
        running: Boolean,
        pending: Boolean,
        selected: Boolean,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(14f), context.dp(10f), context.dp(12f), context.dp(10f))
        background = RippleDrawable(
            ColorStateList.valueOf(Color.parseColor("#184F5BD5")),
            fill(if (selected) PRIMARY_SOFT else "#00000000", 14f),
            null,
        )
        isClickable = true
        setOnClickListener { onClick() }
        onLongClick?.let {
            isLongClickable = true
            setOnLongClickListener { it(); true }
        }

        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(7f), context.dp(7f))
            background = fillInt(
                when {
                    pending -> Color.parseColor(AMBER)
                    running -> Color.parseColor(GREEN)
                    else -> Color.parseColor("#D9D9E5")
                },
                4f,
            )
        }
        addView(dot)
        addView(gap(context, 11f))

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        column.addView(text(context, title, 15f, TEXT, true))
        column.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, context.dp(3f)) })
        column.addView(text(context, subtitle, 12f, MUTED))
        addView(column)

        if (pending) {
            addView(text(context, "待确认", 11.5f, AMBER, true).apply {
                setPadding(context.dp(6f), 0, 0, 0)
            })
        }
    }

    /**
     * A settings row: tinted square icon, label, optional value, chevron.
     * The 52dp rhythm matches the DSH hub in the screenshots.
     */
    fun settingsRow(
        context: Context,
        icon: String,
        hue: Int,
        label: String,
        value: String? = null,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val (bg, fg) = DshIcon.tint(hue)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(14f), context.dp(11f), context.dp(12f), context.dp(11f))
            background = RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#124F5BD5")),
                fill("#00000000", 16f),
                null,
            )
            isClickable = onClick != null
            if (onClick != null) setOnClickListener { onClick() }

            addView(FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(context.dp(34f), context.dp(34f))
                background = fillInt(bg, 11f)
                val g = DshIcon.view(context, icon, fg, 19, 1.6f)
                addView(g, DshUi.centerParams())
            })
            addView(gap(context, 13f))
            addView(text(context, label, 15f, TEXT).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (value != null) {
                addView(text(context, value, 13.5f, MUTED))
                addView(gap(context, 4f))
            }
            addView(DshIcon.view(context, DshIcon.CHEVRON, Color.parseColor("#C3C3CE"), 16, 1.6f))
        }
    }

    fun divider(context: Context, insetDp: Float = 14f): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1,
        ).apply { leftMargin = context.dp(insetDp); rightMargin = context.dp(insetDp) }
        setBackgroundColor(Color.parseColor("#F1F1F7"))
    }

    /**
     * Layout params for a freshly built icon view inside a FrameLayout.
     *
     * 2.19.1 修：以前写的是 `FrameLayout.LayoutParams(glyph.layoutParams)`，
     * 但 DshIcon.view() 造出来的 View 还没 addView 过，layoutParams 是 null，
     * 这个构造函数第一行就去读 source.width → NullPointerException，
     * 首页 onCreate 直接崩，表现出来就是白屏闪退。
     */
    fun centerParams(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { gravity = Gravity.CENTER }

    // ---- inputs ------------------------------------------------------------

    fun searchField(context: Context, hint: String, onChange: (String) -> Unit = {}): EditText =
        EditText(context).apply {
            this.hint = hint
            textSize = 14.5f
            setTextColor(Color.parseColor(TEXT))
            setHintTextColor(Color.parseColor(MUTED))
            setSingleLine(true)
            background = fill("#FFFFFF", 16f, "#EFEFF6")
            setPadding(context.dp(42f), context.dp(11f), context.dp(14f), context.dp(11f))
            elevation = context.dp(0.5f).toFloat()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) = onChange(s?.toString().orEmpty())
            })
        }

    /** Puts a magnifier inside the left padding of [field]. */
    fun withSearchIcon(context: Context, field: EditText): FrameLayout = FrameLayout(context).apply {
        addView(field, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        val icon = DshIcon.view(context, DshIcon.SEARCH, Color.parseColor(MUTED), 18, 1.7f)
        addView(icon, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            leftMargin = context.dp(14f)
        })
    }

    fun chip(context: Context, label: String, selected: Boolean, onClick: () -> Unit): TextView =
        text(context, label, 13f, if (selected) "#FFFFFF" else SUB, selected).apply {
            background = RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#1A4F5BD5")),
                fill(if (selected) "#1F1F2E" else "#FFFFFF", 15f, if (selected) null else "#ECECF4"),
                null,
            )
            setPadding(context.dp(14f), context.dp(7f), context.dp(14f), context.dp(7f))
            setOnClickListener { onClick() }
            isClickable = true
        }

    // ---- structure ---------------------------------------------------------

    /** Full-height page with a scrolling body and generous bottom air. */
    fun page(context: Context): Pair<ScrollView, LinearLayout> {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(14f), 0, context.dp(14f), context.dp(28f))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return scroll to body
    }

    /** Shrinks a view's top margin — used to slide pages under the status bar. */
    fun topMargin(view: View, marginDp: Float) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        lp.topMargin = view.context.dp(marginDp)
        view.layoutParams = lp
    }

    fun statusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else context.dp(24f)
    }

    /** Toast that never queues up behind another one. */
    fun toast(activity: Activity, message: String) {
        android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
