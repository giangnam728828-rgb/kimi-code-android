package ai.kimi.code.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * 「设置 → 手机操控」。
 *
 * 无障碍服务必须由用户在本机系统设置里手动开启（这是 Android 的硬性规定，
 * 任何 App 都绕不过去），所以这一页只做三件事：解释、跳到那个开关、自检。
 */
class PhoneControlActivity : Activity() {

    private lateinit var statusCard: LinearLayout
    private lateinit var resultBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = FrameLayout(this).apply { setBackground(DshUi.background()) }
        setContentView(root)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), statusBarHeight() + dp(10f), dp(16f), dp(32f))
        }
        scroll.addView(body, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        // top bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(DshUi.circleButton(this, DshIcon.BACK, 40f) { finish() })
        bar.addView(DshUi.gap(this, 12f))
        bar.addView(DshUi.text(this, "手机操控", 20f, DshUi.TEXT, true))
        body.addView(bar)
        body.addView(DshUi.spacer(this, 16f))

        statusCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(statusCard)

        body.addView(DshUi.sectionLabel(this, "它能做什么"))
        val can = DshUi.group(this)
        can.addView(DshUi.settingsRow(this, DshIcon.PHONE, 4, "读屏", "看当前界面的文字与控件"))
        can.addView(DshUi.divider(this))
        can.addView(DshUi.settingsRow(this, DshIcon.EDIT, 1, "点按与输入", "按文字点、填表、滚动"))
        can.addView(DshUi.divider(this))
        can.addView(DshUi.settingsRow(this, DshIcon.SHIELD, 3, "系统操作", "返回 / 桌面 / 多任务 / 打开 App"))
        can.addView(DshUi.divider(this))
        can.addView(DshUi.settingsRow(this, DshIcon.DATA, 2, "剪贴板与截图", "互通文本、必要时看截图"))
        body.addView(can)

        body.addView(DshUi.sectionLabel(this, "自检"))
        resultBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = DshUi.fill("#F7F7FB", 16f, "#EFEFF6")
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        }
        resultBox.addView(DshUi.text(this, "开启后点下面的按钮，会读一次当前屏幕。", 12f, DshUi.MUTED, lines = 3))
        body.addView(resultBox)
        body.addView(DshUi.spacer(this, 10f))
        val test = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        test.addView(DshUi.pillButton(this, "读取当前屏幕", true, DshIcon.RUN) { testScreen() })
        test.addView(DshUi.gap(this, 8f))
        test.addView(DshUi.pillButton(this, "返回桌面", false, DshIcon.PHONE) {
            PhoneAccessibilityService.instance?.key("home")
            Toast.makeText(this, "已返回桌面", Toast.LENGTH_SHORT).show()
        })
        body.addView(test)

        body.addView(DshUi.sectionLabel(this, "边界"))
        body.addView(DshUi.text(
            this,
            "· 只能看到屏幕上显示的内容，读不到 App 的内部数据。\n" +
                "· 银行、支付类页面通常禁止截屏/读屏，这是系统限制。\n" +
                "· 每次操作都会记在「运行 → 日志」里，可随时关掉这个开关。\n" +
                "· 模型拿到的只是当前这一步的屏幕文字，不会持续录制。",
            12f, DshUi.SUB, lines = 8,
        ).apply { setPadding(dp(6f), 0, dp(6f), 0) })

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (PhoneAccess.isEnabled(this)) PhoneAccess.ensureBridge(this)
    }

    private fun refreshStatus() {
        if (!this::statusCard.isInitialized) return
        statusCard.removeAllViews()
        val enabled = PhoneAccess.isEnabled(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = DshUi.fill("#FCFFFFFF", 22f, "#F0F0F7")
            elevation = dp(1.5f).toFloat()
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(9f), dp(9f))
            background = DshUi.fillInt(Color.parseColor(if (enabled) DshUi.GREEN else DshUi.AMBER), 5f)
        })
        head.addView(DshUi.gap(this, 8f))
        head.addView(DshUi.text(this, if (enabled) "已开启" else "需要开启无障碍", 17f, DshUi.TEXT, true))
        card.addView(head)
        card.addView(DshUi.text(
            this,
            if (enabled) "模型可以直接读屏、点按、输入、打开 App。"
            else "Android 要求由你本人在系统设置里开启，App 不能代劳。开启后回到这里即可。",
            12.5f, DshUi.MUTED, lines = 3,
        ).apply { setPadding(0, dp(8f), 0, dp(14f)) })

        if (enabled) {
            card.addView(DshUi.pillButton(this, "重新检查", false, DshIcon.REFRESH) { refreshStatus() })
        } else {
            card.addView(DshUi.pillButton(this, "去系统设置开启", true, DshIcon.PHONE) {
                PhoneAccess.openSettings(this)
            })
            card.addView(DshUi.text(
                this,
                "路径：设置 → 无障碍 → 已下载的服务 → Kimi Code 手机操控",
                11.5f, DshUi.MUTED, lines = 2,
            ).apply { setPadding(0, dp(10f), 0, 0) })
        }
        statusCard.addView(card)
    }

    private fun testScreen() {
        val service = PhoneAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "先开启无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }
        val text = service.screen(40)
        resultBox.removeAllViews()
        resultBox.addView(DshUi.text(this, text, 11f, DshUi.SUB, lines = 24).apply {
            typeface = android.graphics.Typeface.MONOSPACE
        })
    }

    private fun statusBarHeight(): Int = DshUi.statusBarHeight(this)
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
}
