package ai.kimi.code.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Settings that live *inside* the workspace.
 *
 * Before 2.12.0 changing a model meant: leave the chat, open the settings tab,
 * edit one model, save (which restarts proot), then re-enter the chat. Users
 * with several models under one API key had to repeat that for every model.
 *
 * This dialog is opened from a small native chip over the WebView. It can
 * switch between models already registered under the current provider, add a
 * new one from the gateway catalogue, or edit the API address / key, and applies
 * everything in place. The port is pinned and the page is never reloaded, so
 * the login state and the conversation survive (see RestartPolicy).
 *
 * The whole View is built in code, like ModelPickerDialog: no new XML ids, no
 * resource-table churn.
 */
object QuickSettingsDialog {

    fun show(
        activity: Activity,
        onRefresh: () -> Unit,
        onChanged: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        var dialog: AlertDialog? = null

        val status = TextView(activity).apply {
            text = "点模型名即可切换，全部共用一个 API 地址和 Key"
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 12f
        }

        fun label(text: String, bold: Boolean = false): TextView = TextView(activity).apply {
            this.text = text
            textSize = 12f
            setTextColor(activity.getColor(if (bold) R.color.text_primary else R.color.text_secondary))
            if (bold) setTypeface(null, Typeface.BOLD)
        }

        fun chip(text: String, onClick: () -> Unit): TextView = TextView(activity).apply {
            this.text = text
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(activity.getColor(R.color.accent))
            }
            setPadding(dp(12), dp(11), dp(12), dp(11))
            setOnClickListener { onClick() }
        }

        fun divider(): View = View(activity).apply {
            setBackgroundColor(Color.rgb(0xEC, 0xEE, 0xF1))
        }

        val baseUrlEdit = EditText(activity).apply {
            hint = "API 地址，例如 https://api.example.com/v1"
            textSize = 13f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val apiKeyEdit = EditText(activity).apply {
            hint = "API Key"
            textSize = 13f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val modelRows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        fun activeIndex(): Int {
            val entries = Prefs.load(activity)
            if (entries.isEmpty()) return -1
            val active = Prefs.activeEntry(activity) ?: return 0
            val found = entries.indexOfFirst { it.modelAlias == active.modelAlias }
            return if (found >= 0) found else 0
        }

        fun toast(msg: String) {
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }

        /** One write + one background restart; the WebView page is left alone. */
        fun applyChange(entries: List<Prefs.Entry>, index: Int, message: String) {
            val written = runCatching {
                Prefs.save(activity, entries)
                ConfigWriter.write(activity, entries, entries.getOrNull(index))
            }.getOrElse {
                toast("保存失败：${it.message}")
                return
            }
            if (written) RuntimeService.restart(activity)
            onChanged()
            toast("$message · 后台生效，页面不刷新")
        }

        fun renderModels() {
            modelRows.removeAllViews()
            val entries = Prefs.load(activity)
            val index = activeIndex()
            if (index < 0) {
                modelRows.addView(label("还没有配置模型，请先到「设置」页添加"))
                status.text = "未配置"
                return
            }
            val entry = entries[index]
            val ids = entry.registeredModels
            ids.forEach { id ->
                val isCurrent = id.equals(entry.model, ignoreCase = true)
                modelRows.addView(TextView(activity).apply {
                    text = if (isCurrent) "✓  $id   （当前）" else "     $id"
                    textSize = 14f
                    setTextColor(activity.getColor(if (isCurrent) R.color.accent else R.color.text_primary))
                    setPadding(dp(8), dp(11), dp(8), dp(11))
                    setOnClickListener {
                        val list = entries.toMutableList()
                        list[index] = list[index].copy(model = id)
                        applyChange(list, index, "已切到 $id")
                        renderModels()
                    }
                })
            }
            status.text = "接入名「${entry.alias}」· 已登记 ${ids.size} 个模型，点名字即可切换"
        }

        fun syncInputs() {
            val entries = Prefs.load(activity)
            val index = activeIndex()
            if (index < 0) return
            val e = entries[index]
            baseUrlEdit.setText(e.baseUrl.ifBlank { e.preset.baseUrl })
            apiKeyEdit.setText(e.apiKey)
        }

        fun saveEndpoint() {
            val entries = Prefs.load(activity).toMutableList()
            val index = activeIndex()
            if (index < 0) {
                toast("请先到「设置」页添加一个模型")
                return
            }
            val cur = entries[index]
            val url = baseUrlEdit.text.toString().trim().ifBlank { cur.preset.baseUrl }
            val key = apiKeyEdit.text.toString().trim()
            if (key.isBlank()) {
                toast("API Key 不能为空")
                return
            }
            entries[index] = cur.copy(baseUrl = url, apiKey = key)
            applyChange(entries, index, "已保存接入信息")
        }

        fun addModel() {
            val entries = Prefs.load(activity).toMutableList()
            val index = activeIndex()
            if (index < 0) {
                toast("请先到「设置」页添加一个模型")
                return
            }
            val url = baseUrlEdit.text.toString().trim().ifBlank { entries[index].preset.baseUrl }
            val key = apiKeyEdit.text.toString().trim()
            if (url.isBlank() || key.isBlank()) {
                toast("先填好 API 地址和 Key")
                return
            }
            val type = entries[index].type
            val current = entries[index].model
            // Persist the edited endpoint first so the picker probes the right
            // gateway and nothing typed here is lost when the dialog reopens.
            entries[index] = entries[index].copy(baseUrl = url, apiKey = key)
            Prefs.save(activity, entries)
            dialog?.dismiss()
            ModelPickerDialog.show(
                activity = activity,
                baseUrl = url,
                apiKey = key,
                type = type,
                current = current,
                cached = emptyList(),
                onCache = {},
                onPicked = { picked ->
                    val list = Prefs.load(activity).toMutableList()
                    val i = activeIndex()
                    if (i >= 0) {
                        val cur = list[i]
                        val all = (cur.models + cur.model + picked.id)
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                        list[i] = cur.copy(
                            model = picked.id,
                            models = all.filterNot { it.equals(picked.id, ignoreCase = true) },
                            baseUrl = url,
                            apiKey = key,
                        )
                        applyChange(list, i, "已加入 ${picked.id}")
                    }
                    show(activity, onRefresh, onChanged)
                },
            )
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        root.addView(label("随手切换 · 不用退出聊天", bold = true).apply { textSize = 15f })
        root.addView(status)
        root.addView(divider(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1,
        ).apply { topMargin = dp(10); bottomMargin = dp(6) })

        val modelScroll = ScrollView(activity)
        modelScroll.addView(
            modelRows,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(modelScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(160),
        ))

        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(
            chip("＋ 从目录添加模型") { addModel() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            },
        )
        actions.addView(
            chip("仅连不上时刷新") { onRefresh(); dialog?.dismiss() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })

        root.addView(label("接口与密钥（上面所有模型共用）", bold = true).apply {
            setPadding(0, dp(12), 0, dp(4))
        })
        root.addView(baseUrlEdit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(apiKeyEdit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(chip("保存并生效") { saveEndpoint() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        root.addView(label("切换后后台自动重连，聊天记录与登录态保留；网页里也可用「更多模型」直接切。").apply {
            setPadding(0, dp(10), 0, 0)
        })

        syncInputs()
        renderModels()
        dialog = AlertDialog.Builder(activity)
            .setView(root)
            .setNegativeButton("关闭", null)
            .create()
        dialog?.show()
    }
}
