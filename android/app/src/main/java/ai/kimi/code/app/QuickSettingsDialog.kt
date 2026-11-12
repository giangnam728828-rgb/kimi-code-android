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
 * Configuration for one provider (one API address + key), opened from the
 * settings page -- NOT from a floating chip over the WebView. An earlier build
 * put a native chip at the bottom-right of the workspace; it covered the web
 * page's send button and duplicated the web UI's own model dropdown.
 *
 * Opening this dialog immediately lists the whole `/v1/models` catalogue of the
 * endpoint, not just the models already written to config.toml. One tap on any
 * row registers that model under the provider and makes it the default, so a
 * single visit covers every model behind the same key. After that the web UI's
 * own dropdown switches between all of them with no save and no restart.
 *
 * The whole View is built in code, like ModelPickerDialog: no new XML ids, no
 * resource-table churn.
 */
object QuickSettingsDialog {

    fun show(
        activity: Activity,
        onRefresh: () -> Unit,
        onChanged: () -> Unit,
        onRegisterAll: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        var dialog: AlertDialog? = null

        val status = TextView(activity).apply {
            text = "这里只配置；日常切换请用工作台输入框旁的模型下拉"
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

        /** Upstream ids of this endpoint, filled in by [loadCatalog]. */
        var catalogIds: List<String> = emptyList()
        var fetching = false

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

        /** 分组标题 + 组间留白：不同分组之间明确隔开，不再平铺成一列。 */
        fun groupHeader(title: String): TextView = TextView(activity).apply {
            text = title
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(activity.getColor(R.color.text_secondary))
            setBackgroundColor(Color.parseColor("#F2F3F5"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        fun groupSeparator(): View = View(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
        }

        fun renderModels() {
            modelRows.removeAllViews()
            val entries = Prefs.load(activity)
            if (entries.isEmpty()) {
                modelRows.addView(label("还没有配置模型，请先到「设置」页添加"))
                status.text = "未配置"
                return
            }
            val activeIndex = activeIndex()
            entries.forEachIndexed { groupIndex, entry ->
                if (groupIndex > 0) modelRows.addView(groupSeparator())
                val ids = entry.registeredModels
                modelRows.addView(groupHeader("${entry.alias}  (${ids.size} 个模型)"))
                ids.forEach { id ->
                    val isCurrent = id.equals(entry.model, ignoreCase = true)
                    modelRows.addView(TextView(activity).apply {
                        text = if (isCurrent) "✓  $id   （当前）" else "     $id"
                        textSize = 14f
                        setTextColor(
                            activity.getColor(if (isCurrent) R.color.accent else R.color.text_primary),
                        )
                        setPadding(dp(8), dp(11), dp(8), dp(11))
                        setOnClickListener {
                            val list = entries.toMutableList()
                            list[groupIndex] = list[groupIndex].copy(model = id)
                            applyChange(list, groupIndex, "已切到 $id")
                            renderModels()
                        }
                    })
                }
                modelRows.addView(divider(), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1,
                ))
            }
            status.text = "共 ${entries.size} 个分组 · " +
                "${entries.sumOf { it.registeredModels.size }} 个模型；工作台「更多模型」里按分组隔开显示" +
                if (activeIndex >= 0) "（当前默认分组：${entries[activeIndex].alias}）" else ""
        }

        fun syncInputs() {
            val entries = Prefs.load(activity)
            val index = activeIndex()
            if (index < 0) return
            val e = entries[index]
            baseUrlEdit.setText(e.baseUrl.ifBlank { e.preset.baseUrl })
            apiKeyEdit.setText(e.apiKey)
        }

        /** Read the whole catalogue so the list is complete on first open. */
        fun loadCatalog() {
            if (fetching) return
            val entries = Prefs.load(activity)
            val index = activeIndex()
            if (index < 0) return
            val entry = entries[index]
            val url = baseUrlEdit.text.toString().trim().ifBlank { entry.baseUrl.ifBlank { entry.preset.baseUrl } }
            val key = apiKeyEdit.text.toString().trim().ifBlank { entry.apiKey }
            if (url.isBlank() || key.isBlank()) {
                status.text = "先填 API 地址与 Key，就能列出这个接口的全部模型"
                return
            }
            fetching = true
            status.text = "正在读取该接口的模型列表…"
            Thread {
                val result = ModelCatalog.fetch(url, key, entry.type)
                activity.runOnUiThread {
                    fetching = false
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if (result.ids.isNotEmpty()) catalogIds = result.ids
                    renderModels()
                    if (result.error != null) {
                        status.text = "读取模型列表失败：${result.error}；仍可点击下面已登记的模型"
                    }
                }
            }.apply { isDaemon = true }.start()
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
            loadCatalog()
        }

        /** One tap registers the whole catalogue; no per-model picking loop. */
        fun registerAll() {
            dialog?.dismiss()
            onRegisterAll()
        }

        /** 2.15.0: tick several models at once and keep them in the same group. */
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
            val current = entries[index]
            // Persist the edited endpoint first so the picker probes the right
            // gateway and nothing typed here is lost when the dialog reopens.
            entries[index] = current.copy(baseUrl = url, apiKey = key)
            Prefs.save(activity, entries)
            dialog?.dismiss()
            // 一次可勾选多个模型：全部登记进当前分组，工作台下拉里同组显示。
            ModelMultiPickerDialog.show(
                activity = activity,
                baseUrl = url,
                apiKey = key,
                type = type,
                already = current.registeredModels,
                cached = emptyList(),
                onCache = {},
                onPicked = { picked ->
                    val list = Prefs.load(activity).toMutableList()
                    val i = activeIndex()
                    if (i >= 0) {
                        val e = list[i]
                        val (defaultModel, rest) = ModelGroup.mergePicked(
                            e.model, e.models, picked.map { it.id },
                        )
                        list[i] = e.copy(
                            model = defaultModel,
                            models = rest,
                            baseUrl = url,
                            apiKey = key,
                        )
                        applyChange(list, i, "已加入 ${picked.size} 个模型到分组「${e.alias}」")
                    }
                    show(activity, onRefresh, onChanged, onRegisterAll)
                },
            )
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        root.addView(label("模型分组 · 每个分组都能添加多个模型", bold = true).apply { textSize = 15f })
        root.addView(status)

        val modelScroll = ScrollView(activity)
        modelScroll.addView(
            modelRows,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(modelScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(190),
        ))

        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(
            chip("＋ 批量添加模型") { addModel() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            },
        )
        actions.addView(
            chip("刷新列表") { loadCatalog() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            },
        )
        actions.addView(
            chip("一键登记全部") { registerAll() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })

        root.addView(divider(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1,
        ).apply { topMargin = dp(12); bottomMargin = dp(6) })

        root.addView(label("接口与密钥（上面所有模型共用）", bold = true).apply {
            setPadding(0, dp(6), 0, dp(4))
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
        root.addView(TextView(activity).apply {
            text = "点上面的模型即可切换并登记；保存后后台重连，聊天记录与登录态保留。"
            textSize = 11f
            setTextColor(activity.getColor(R.color.text_muted))
            setPadding(0, dp(10), 0, 0)
        })
        root.addView(TextView(activity).apply {
            text = "重载工作台页面"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(activity.getColor(R.color.accent))
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener { onRefresh(); dialog?.dismiss() }
        })

        syncInputs()
        renderModels()
        dialog = AlertDialog.Builder(activity)
            .setView(root)
            .setNegativeButton("关闭", null)
            .create()
        dialog?.show()
        loadCatalog()
    }
}
