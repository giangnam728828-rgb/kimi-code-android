package ai.kimi.code.app

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import ai.kimi.code.app.ProviderStore.ModelSpec
import ai.kimi.code.app.ProviderStore.ProviderSpec

/**
 * Eta 风格的「模型供应商」两级管理页（2.16.2）。
 *
 * 本版要点：
 * 1. **草稿制**：点「新增 OpenAI / Anthropic / Gemini / 目录项」只进入一个
 *    *未落盘* 的编辑详情，绝不写 ProviderStore；只有点设定页「保存」才真正
 *    加入列表。直接返回 = 丢弃，修掉「点进去看一眼就被保存」的严重 bug。
 * 2. **全 App 统一弹层**：目录 / Endpoint / 请求头 / 删除确认 / 编辑模型 /
 *    拉取候选 / 新增自订模型，全部走 [EtaSheet] 底部滑入面板，不再有系统
 *    AlertDialog 那套居中方块与不一致动效。
 * 3. **按 ETA 三张截图还原**：编辑模型、拉取后新增模型、新增自订模型。
 * 4. **改名生效**：名称写进 config.toml（[providers.*].name /
 *    [models.*].display_name），保存后重启并刷新工作台。
 * 5. **全 App 动效**：页面切换 / Tab / 列表 / 按钮 / 弹层都由 [EtaMotion] 驱动。
 */
class ProviderActivity : android.app.Activity() {

    companion object {
        /** MainActivity.onResume 据此刷新界面与网页模型列表。 */
        @JvmStatic
        var changed = false
    }

    private lateinit var titleView: TextView
    private lateinit var pageHost: FrameLayout
    private var providers: MutableList<ProviderSpec> = mutableListOf()
    private var current: ProviderSpec? = null
    /** 当前详情是否为「尚未保存的草稿」。草稿不在 providers 里，返回即丢弃。 */
    private var isDraft = false
    private var detailTab = 0
    private var searchText = ""
    private var busy = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun s(id: Int): String = getString(id)
    private fun s(id: Int, vararg args: Any): String = getString(id, *args)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        providers = ProviderStore.load(this)
        buildShell()
        showList()
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(EtaUi.BG))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(6))
        }
        bar.addView(TextView(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(Color.parseColor(EtaUi.TEXT))
            setPadding(0, 0, dp(18), 0)
            setOnClickListener { onBack() }
            EtaMotion.press(this@ProviderActivity, this)
        })
        titleView = TextView(this).apply {
            text = s(R.string.provider_title)
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(EtaUi.TEXT))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(titleView)
        root.addView(bar)

        pageHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        root.addView(pageHost)
        setContentView(root)
        // 全 App 动效：进入本页时整体淡入上移
        EtaMotion.enter(this, root, fromDp = 10f)
    }

    private fun onBack() {
        if (current != null) {
            val discarded = isDraft
            showList()
            if (discarded) toast(s(R.string.provider_draft_discarded))
        } else {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, R.anim.eta_slide_out)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = onBack()

    private fun scroll(content: LinearLayout): ScrollView = ScrollView(this).apply {
        setBackgroundColor(Color.parseColor(EtaUi.BG))
        isFillViewport = true
        addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        content.setPadding(dp(20), dp(8), dp(20), dp(40))
    }

    private fun showPage(view: View) {
        pageHost.removeAllViews()
        pageHost.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        // 全 App 动效：每次页面切换统一入场，列表↔详情手感一致
        EtaMotion.enter(this, view, fromDp = 14f)
    }

    // ------------------------------------------------------------- 一级：列表

    private fun showList() {
        current = null
        isDraft = false
        searchText = ""
        titleView.text = s(R.string.provider_title)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(EtaUi.bigTitle(this, s(R.string.provider_title)))
        c.addView(EtaUi.searchBox(this, s(R.string.provider_search)) { q ->
            searchText = q.trim()
            renderListBody()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) })

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(body)
        listBody = body
        renderListBody()
        showPage(scroll(c))
    }

    private var listBody: LinearLayout? = null

    private fun renderListBody() {
        val body = listBody ?: return
        body.removeAllViews()

        // 新增供应商
        body.addView(EtaUi.sectionLabel(this, s(R.string.provider_add_section)))
        val addCard = EtaUi.card(this)
        addCard.addView(EtaUi.actionRow(
            this, "▦", s(R.string.provider_from_catalog),
            s(R.string.provider_from_catalog_sub),
            { openCatalog() },
        ))
        addCard.addView(EtaUi.divider(this))
        addCard.addView(EtaUi.actionRow(
            this, "◎", s(R.string.provider_add_openai),
            s(R.string.provider_add_openai_sub),
            { addProvider(ProviderStore.TYPE_OPENAI) }, "#EDE7FB", "#6D4BC3",
        ))
        addCard.addView(EtaUi.divider(this))
        addCard.addView(EtaUi.actionRow(
            this, "A", s(R.string.provider_add_anthropic),
            s(R.string.provider_add_anthropic_sub),
            { addProvider(ProviderStore.TYPE_ANTHROPIC) }, "#FBEDE4", "#B4602A",
        ))
        addCard.addView(EtaUi.divider(this))
        addCard.addView(EtaUi.actionRow(
            this, "✦", s(R.string.provider_add_gemini),
            s(R.string.provider_add_gemini_sub),
            { addProvider(ProviderStore.TYPE_GEMINI) }, "#E5F3EA", "#1F8A4C",
        ))
        body.addView(addCard, matchWidth().apply { bottomMargin = dp(6) })

        // 已设定供应商（草稿绝不在这里，因为它没进 providers）
        val q = searchText.lowercase()
        val shown = providers.filter {
            q.isBlank() || it.displayTitle.lowercase().contains(q) ||
                it.baseUrl.lowercase().contains(q) ||
                it.usableModels.any { m -> m.id.lowercase().contains(q) }
        }
        body.addView(EtaUi.sectionLabel(this, s(R.string.provider_configured, providers.size)))
        if (shown.isEmpty()) {
            body.addView(TextView(this).apply {
                text = s(R.string.provider_empty)
                textSize = 13f
                setTextColor(Color.parseColor(EtaUi.MUTED))
                setPadding(dp(4), dp(10), dp(4), dp(10))
            })
        } else {
            val listCard = EtaUi.card(this)
            shown.forEachIndexed { i, p ->
                if (i > 0) listCard.addView(EtaUi.divider(this))
                listCard.addView(providerRow(p))
            }
            body.addView(listCard, matchWidth())
            EtaMotion.stagger(this, listCard, stepMs = 24, cap = 8)
        }

        // App 级开关（旧设置页被替换后搬到这里）
        body.addView(EtaUi.sectionLabel(this, s(R.string.provider_app_prefs)))
        val prefCard = EtaUi.card(this, 18)
        prefCard.addView(prefSwitch(
            s(R.string.provider_pref_swipe), Prefs.swipeBack(this),
        ) { Prefs.setSwipeBack(this, it) })
        prefCard.addView(EtaUi.divider(this))
        prefCard.addView(prefSwitch(
            s(R.string.provider_pref_lan), Prefs.lan(this),
        ) {
            Prefs.setLan(this, it)
            toast(s(R.string.provider_pref_restart_hint))
        })
        prefCard.addView(EtaUi.divider(this))
        prefCard.addView(prefSwitch(
            s(R.string.provider_pref_awake), Prefs.keepAwake(this),
        ) { Prefs.setKeepAwake(this, it) })
        body.addView(prefCard, matchWidth())
        body.addView(TextView(this).apply {
            text = s(R.string.provider_no_auto_enter)
            textSize = 12f
            setTextColor(Color.parseColor(EtaUi.MUTED))
            setPadding(dp(4), dp(10), dp(4), dp(4))
        }, matchWidth())
    }

    private fun prefSwitch(label: String, initial: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            text = label
            textSize = 15f
            isChecked = initial
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnCheckedChangeListener { _, v -> onChange(v) }
            EtaMotion.press(this@ProviderActivity, this)
        }

    private fun providerRow(p: ProviderSpec): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setOnClickListener { openDetail(p.alias) }
        EtaMotion.press(this@ProviderActivity, this)
        val info = LinearLayout(this@ProviderActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@ProviderActivity).apply {
                text = p.displayTitle
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(EtaUi.TEXT))
            })
            addView(TextView(this@ProviderActivity).apply {
                text = p.baseUrl.ifBlank { s(R.string.provider_no_url) }
                textSize = 12f
                setTextColor(Color.parseColor(EtaUi.SUB))
                setPadding(0, dp(3), 0, dp(2))
            })
            addView(TextView(this@ProviderActivity).apply {
                text = buildString {
                    append(ProviderStore.typeLabel(p.type))
                    append(" · ")
                    append(s(R.string.provider_model_count, p.usableModels.size))
                    if (!p.enabled) append(" · ").append(s(R.string.provider_disabled))
                }
                textSize = 12f
                setTextColor(Color.parseColor(EtaUi.MUTED))
            })
        }
        addView(info)
        addView(EtaUi.radio(this@ProviderActivity, p.enabled && isActive(p)))
    }

    private fun isActive(p: ProviderSpec): Boolean =
        Prefs.activeEntry(this)?.alias == p.alias

    // ------------------------------------------------------------- 目录 / 新增

    private fun openCatalog() {
        val entries = ProviderStore.CATALOG
        EtaSheet.show(this, s(R.string.provider_from_catalog), tall = false) { sheet ->
            entries.forEachIndexed { i, e ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4), dp(12), dp(4), dp(12))
                    isClickable = true
                    setOnClickListener {
                        sheet.dismiss()
                        addProvider(e.type, e)
                    }
                    EtaMotion.press(this@ProviderActivity, this)
                    addView(TextView(this@ProviderActivity).apply {
                        text = e.name
                        textSize = 16f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor(EtaUi.TEXT))
                    })
                    addView(TextView(this@ProviderActivity).apply {
                        text = e.hint
                        textSize = 12f
                        setTextColor(Color.parseColor(EtaUi.SUB))
                        setPadding(0, dp(3), 0, 0)
                    })
                }
                sheet.body.addView(item, matchWidth())
                if (i < entries.size - 1) sheet.body.addView(EtaUi.divider(this))
            }
            val cancel = EtaSheet.button(
                this, s(R.string.provider_btn_cancel), filled = false,
            ) { sheet.dismiss() }
            sheet.footer.addView(cancel, matchWidth())
        }
    }

    /**
     * 2.16.2 严重 bug 修复：新增供应商 = **草稿**，绝不落盘。
     * 只在设定页点「保存并新增」时才加入 providers 并写盘；直接返回丢弃。
     */
    private fun addProvider(type: String, preset: ProviderStore.CatalogEntry? = null) {
        val used = providers.map { it.alias }.toSet()
        val base = preset?.key ?: when (type) {
            ProviderStore.TYPE_ANTHROPIC -> "anthropic"
            ProviderStore.TYPE_GEMINI -> "gemini"
            else -> "custom"
        }
        val alias = ProviderStore.uniqueAlias(base, used)
        val draft = ProviderSpec(
            alias = alias,
            name = preset?.name ?: ProviderStore.typeLabel(type),
            type = type,
            baseUrl = preset?.baseUrl.orEmpty(),
            endpointMode = preset?.endpointMode ?: when (type) {
                ProviderStore.TYPE_ANTHROPIC -> ProviderStore.ENDPOINT_MESSAGES
                ProviderStore.TYPE_GEMINI -> ProviderStore.ENDPOINT_INTERACTIONS
                else -> ProviderStore.ENDPOINT_CHAT
            },
            models = emptyList(),
        )
        current = draft
        isDraft = true
        detailTab = 0
        renderDetail()
    }

    // ------------------------------------------------------------- 二级：详情

    private fun openDetail(alias: String) {
        current = providers.firstOrNull { it.alias == alias } ?: return
        isDraft = false
        detailTab = 0
        renderDetail()
    }

    private fun renderDetail() {
        val p = current ?: return
        titleView.text = p.displayTitle
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(EtaUi.bigTitle(this, p.displayTitle))

        var tabSelect: ((Int) -> Unit)? = null
        val (seg, select) = EtaUi.segmented(
            this,
            listOf(s(R.string.provider_tab_settings), s(R.string.provider_tab_models)),
        ) { index ->
            detailTab = index
            tabSelect?.invoke(index)
            renderDetailBody()
        }
        tabSelect = select
        c.addView(seg, matchWidth().apply { bottomMargin = dp(10) })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(body)
        detailBody = body
        select(detailTab)
        renderDetailBody()
        showPage(scroll(c))
    }

    private var detailBody: LinearLayout? = null

    private fun renderDetailBody() {
        if (detailTab == 0) renderSettingsTab() else renderModelsTab()
        detailBody?.let { EtaMotion.enter(this, it, fromDp = 8f) }
    }

    // ------------------------------------------------------------- 设定 Tab

    private fun renderSettingsTab() {
        val body = detailBody ?: return
        body.removeAllViews()
        val p = current ?: return

        if (isDraft) {
            body.addView(TextView(this).apply {
                text = s(R.string.provider_draft_hint)
                textSize = 13f
                setTextColor(Color.parseColor("#B4602A"))
                background = EtaUi.rounded("#FBEDE4", 12)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }, matchWidth().apply { bottomMargin = dp(12) })
        }

        body.addView(EtaUi.sectionLabel(this, s(R.string.provider_connection)))
        val card = EtaUi.card(this, 18)

        val (nameBox, nameInput) = EtaUi.fieldBox(
            this, s(R.string.provider_field_name), p.name,
            s(R.string.provider_field_name_hint),
        )
        card.addView(nameBox, matchWidth().apply { bottomMargin = dp(10) })

        val (urlBox, urlInput) = EtaUi.fieldBox(
            this, "Base URL", p.baseUrl, "https://api.example.com/v1",
        )
        card.addView(urlBox, matchWidth().apply { bottomMargin = dp(10) })

        val (keyBox, keyInput) = EtaUi.fieldBox(
            this, "API Key", p.apiKey, "sk-…", password = true,
        )
        val keyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        keyRow.addView(keyBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        keyRow.addView(TextView(this).apply {
            text = "◉"
            textSize = 19f
            setTextColor(Color.parseColor(EtaUi.SUB))
            setPadding(dp(14), dp(10), dp(4), dp(10))
            setOnClickListener {
                val hidden = keyInput.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
                keyInput.inputType = if (hidden)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                keyInput.setSelection(keyInput.text.length)
            }
        })
        card.addView(keyRow, matchWidth().apply { bottomMargin = dp(4) })

        card.addView(EtaUi.linkRow(
            this, s(R.string.provider_endpoint),
            ProviderStore.endpointLabel(p.endpointMode),
        ) { chooseEndpoint() })
        card.addView(EtaUi.divider(this))
        card.addView(EtaUi.linkRow(this, s(R.string.provider_test), "") { probeConnection() })
        card.addView(EtaUi.divider(this))
        card.addView(EtaUi.linkRow(
            this, s(R.string.provider_headers),
            if (p.customHeaders.isBlank()) s(R.string.provider_headers_unset)
            else s(R.string.provider_headers_set),
        ) { editHeaders() })
        body.addView(card, matchWidth())

        body.addView(EtaUi.sectionLabel(this, s(R.string.provider_preference)))
        val prefCard = EtaUi.card(this, 18)
        val sw = Switch(this).apply {
            text = s(R.string.provider_enable)
            textSize = 16f
            isChecked = p.enabled
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnCheckedChangeListener { _, v -> updateCurrent { it.copy(enabled = v) } }
        }
        prefCard.addView(sw)
        body.addView(prefCard, matchWidth())

        // 保存按钮：草稿 = 「保存并新增」，已保存 = 「保存」
        body.addView(EtaUi.primaryButton(
            this,
            if (isDraft) s(R.string.provider_draft_save) else s(R.string.provider_save),
        ) {
            val name = nameInput.text.toString().trim()
            val url = urlInput.text.toString().trim()
            val key = keyInput.text.toString().trim()
            // 草稿只有真正点「保存并新增」才落盘；名称和 API Key 是可用性的
            // 最低要求，缺一个就提示，绝不把半成品写进「已设定」。
            if (isDraft && (name.isBlank() || key.isBlank())) {
                toast(s(R.string.provider_need_fields))
                return@primaryButton
            }
            if (isDraft) {
                val base = current ?: return@primaryButton
                // 新建时 alias 直接取用户起的名字：工作台的分组标题就是 alias，
                // 这样从第一天起看到的组名就是「DeepSeek」而不是 custom-1。
                val used = providers.map { it.alias }.toSet()
                val alias = ProviderStore.uniqueAlias(name, used)
                ProviderStore.reviveAlias(this, alias)
                val built = base.copy(alias = alias, name = name, baseUrl = url, apiKey = key)
                providers.add(built)
                current = built
                isDraft = false
            } else {
                val before = current
                // 2.16.2：改名 = 重命名分组。后端 `[providers.*]` 只透传段名，
                // 没有独立的显示名，所以只有把 alias 一起迁走，工作台的「更多
                // 模型」分组标题才会跟着变。旧 alias 记入退役表，其悬空历史段
                // 在重建 config.toml 时被清掉。
                val renamed = before != null && name.isNotBlank() && name != before.name
                if (renamed && before != null) {
                    val others = providers.filter { it.alias != before.alias }
                        .map { it.alias }.toSet()
                    val desired = ProviderStore.uniqueAlias(name, others)
                    if (desired != before.alias) {
                        ProviderStore.retireAlias(this, before.alias, desired)
                        val idx = providers.indexOfFirst { it.alias == before.alias }
                        if (idx >= 0) {
                            providers[idx] = providers[idx].copy(
                                alias = desired, name = name,
                                baseUrl = url, apiKey = key,
                            )
                            current = providers[idx]
                            titleView.text = providers[idx].displayTitle
                        }
                    } else {
                        updateCurrent { it.copy(name = name, baseUrl = url, apiKey = key) }
                    }
                } else {
                    updateCurrent {
                        it.copy(name = name.ifBlank { it.alias }, baseUrl = url, apiKey = key)
                    }
                }
            }
            persistAndNotify(restart = true)
            toast(s(R.string.provider_saved))
            renderDetail()   // 刷新标题 / 去掉草稿提示
        }, matchWidth().apply { topMargin = dp(22) })

        if (!isDraft) {
            body.addView(TextView(this).apply {
                text = s(R.string.provider_delete)
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#DC2626"))
                setPadding(dp(10), dp(22), dp(10), dp(10))
                setOnClickListener { confirmDelete() }
            }, matchWidth())
        }
    }

    private fun chooseEndpoint() {
        val modes = listOf(
            ProviderStore.ENDPOINT_CHAT,
            ProviderStore.ENDPOINT_RESPONSES,
            ProviderStore.ENDPOINT_MESSAGES,
            ProviderStore.ENDPOINT_INTERACTIONS,
        )
        EtaSheet.show(this, s(R.string.provider_endpoint), tall = false) { sheet ->
            modes.forEachIndexed { i, mode ->
                sheet.body.addView(TextView(this).apply {
                    text = ProviderStore.endpointLabel(mode)
                    textSize = 16f
                    setTextColor(Color.parseColor(EtaUi.TEXT))
                    setPadding(dp(4), dp(15), dp(4), dp(15))
                    setOnClickListener {
                        updateCurrent { it.copy(endpointMode = mode) }
                        sheet.dismiss()
                        renderDetailBody()
                    }
                    EtaMotion.press(this@ProviderActivity, this)
                })
                if (i < modes.size - 1) sheet.body.addView(EtaUi.divider(this))
            }
        }
    }

    private fun probeConnection() {
        val p = current ?: return
        val model = p.effectiveDefault
        if (p.baseUrl.isBlank() || p.apiKey.isBlank() || model.isBlank()) {
            toast(s(R.string.provider_need_fields))
            return
        }
        toast(s(R.string.provider_testing))
        val base = p.baseUrl
        val key = p.apiKey
        val type = p.type
        Thread {
            val r = ModelCatalog.probe(base, key, model, type)
            runOnUiThread { if (!isFinishing && !isDestroyed) toast(r.message) }
        }.apply { isDaemon = true }.start()
    }

    private fun editHeaders() {
        val p = current ?: return
        EtaSheet.show(this, s(R.string.provider_headers), tall = false) { sheet ->
            val input = EditText(this).apply {
                setText(p.customHeaders)
                hint = s(R.string.provider_headers_hint)
                textSize = 14f
                background = EtaUi.rounded(EtaUi.FIELD, 14)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3
            }
            sheet.body.addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            val (row, _) = EtaSheet.dualButtons(
                this, s(R.string.provider_btn_cancel), s(R.string.action_save),
                onCancel = { sheet.dismiss() },
                onConfirm = {
                    updateCurrent { it.copy(customHeaders = input.text.toString().trim()) }
                    sheet.dismiss()
                    renderDetailBody()
                },
            )
            sheet.footer.addView(row)
        }
    }

    private fun confirmDelete() {
        val p = current ?: return
        EtaSheet.confirm(
            this,
            s(R.string.provider_delete_confirm, p.displayTitle),
            s(R.string.provider_remove_model_note),
            s(R.string.action_delete),
        ) {
            providers.removeAll { it.alias == p.alias }
            ProviderStore.save(this, providers)
            changed = true
            showList()
        }
    }

    // ------------------------------------------------------------- 模型 Tab

    private fun renderModelsTab() {
        val body = detailBody ?: return
        body.removeAllViews()
        val p = current ?: return

        if (isDraft) {
            body.addView(TextView(this).apply {
                text = s(R.string.provider_draft_hint)
                textSize = 13f
                setTextColor(Color.parseColor("#B4602A"))
                background = EtaUi.rounded("#FBEDE4", 12)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }, matchWidth().apply { bottomMargin = dp(12) })
        }

        body.addView(EtaUi.sectionLabel(this, s(R.string.provider_model_manage)))
        val manageCard = EtaUi.card(this, 18)
        manageCard.addView(EtaUi.actionRow(
            this, "⇩", s(R.string.provider_fetch_remote),
            s(R.string.provider_fetch_remote_sub, p.baseUrl.ifBlank { "Base URL" }),
            { fetchRemote() },
        ))
        manageCard.addView(EtaUi.divider(this))
        manageCard.addView(EtaUi.actionRow(
            this, "+", s(R.string.provider_add_custom_model),
            s(R.string.provider_add_custom_model_sub),
            { addCustomModel() },
        ))
        body.addView(manageCard, matchWidth().apply { bottomMargin = dp(4) })

        body.addView(EtaUi.searchBox(this, s(R.string.provider_model_search)) { q ->
            searchText = q.trim()
            renderModelList()
        }, matchWidth().apply { topMargin = dp(12); bottomMargin = dp(4) })

        val listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(listHost)
        modelListHost = listHost
        renderModelList()
    }

    private var modelListHost: LinearLayout? = null

    private fun renderModelList() {
        val host = modelListHost ?: return
        host.removeAllViews()
        val p = current ?: return
        val q = searchText.lowercase()
        val shown = p.usableModels.filter {
            q.isBlank() || it.id.lowercase().contains(q) || it.label.lowercase().contains(q)
        }
        host.addView(EtaUi.sectionLabel(this, s(R.string.provider_model_list, p.usableModels.size)))
        host.addView(TextView(this).apply {
            text = s(R.string.provider_model_row_hint)
            textSize = 12f
            setTextColor(Color.parseColor(EtaUi.MUTED))
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        if (shown.isEmpty()) {
            host.addView(TextView(this).apply {
                text = s(R.string.provider_model_empty)
                textSize = 13f
                setTextColor(Color.parseColor(EtaUi.MUTED))
                setPadding(dp(4), dp(10), dp(4), dp(10))
            })
        } else {
            val card = EtaUi.card(this, 18)
            shown.forEachIndexed { i, m ->
                if (i > 0) card.addView(EtaUi.divider(this))
                card.addView(modelRow(p, m))
            }
            host.addView(card, matchWidth())
            EtaMotion.stagger(this, card, stepMs = 22, cap = 8)
        }
    }

    private fun modelRow(p: ProviderSpec, m: ModelSpec): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(12), dp(16))
            // 点整行进该模型的单独设置（ETA 截图里的小齿轮键已去掉）
            isClickable = true
            setOnClickListener { editModel(p, m) }
            EtaMotion.press(this@ProviderActivity, this)
            val info = LinearLayout(this@ProviderActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@ProviderActivity).apply {
                    text = m.label
                    textSize = 16f
                    setTextColor(Color.parseColor(EtaUi.TEXT))
                })
                addView(TextView(this@ProviderActivity).apply {
                    text = m.id
                    textSize = 12f
                    setTextColor(Color.parseColor(EtaUi.SUB))
                    setPadding(0, dp(2), 0, dp(5))
                })
                val tags = LinearLayout(this@ProviderActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                tags.addView(EtaUi.chip(this@ProviderActivity,
                    s(R.string.provider_context, m.contextSize / 1024)))
                if (m.reasoning) tags.addView(
                    EtaUi.chip(this@ProviderActivity, s(R.string.provider_reasoning)),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { leftMargin = dp(6) },
                )
                addView(tags)
            }
            addView(info)
            // 右侧圆圈：设为该分组默认模型（独立消费点击，不误触整行）
            addView(EtaUi.radio(this@ProviderActivity, m.id == p.effectiveDefault).apply {
                setOnClickListener {
                    updateCurrent { it.copy(defaultModel = m.id) }
                    val key = ConfigMerger.modelKey(p.alias, m.id)
                    runCatching { ConfigWriter.setDefaultModel(this@ProviderActivity, key, m.contextSize) }
                    persistAndNotify(restart = false)
                    renderModelList()
                    toast(s(R.string.provider_default_set, m.label))
                    EtaMotion.pop(this@ProviderActivity, this)
                }
            })
        }

    private fun fetchRemote() {
        val p = current ?: return
        if (busy) return
        if (p.baseUrl.isBlank() || p.apiKey.isBlank()) {
            toast(s(R.string.provider_need_fields))
            return
        }
        busy = true
        toast(s(R.string.provider_fetching))
        val base = p.baseUrl
        val key = p.apiKey
        val type = p.type
        val alias = p.alias
        Thread {
            val r = ModelCatalog.fetch(base, key, type)
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (r.error != null) {
                    toast(r.error ?: s(R.string.provider_fetch_failed))
                    return@runOnUiThread
                }
                // 拉取只列候选，绝不自动加入；用户勾选确认后才写清单
                showAddModelPicker(r.ids, alias)
            }
        }.apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------- ETA 三界面

    private val CONTEXT_PRESETS = listOf(
        "128K" to 131072, "200K" to 204800, "256K" to 262144, "1M" to 1048576,
    )
    private val EFFORT_LEVELS = listOf(
        "Off", "Minimal", "Low", "Medium", "High", "XHigh", "Max",
    )

    private fun tokensText(n: Int): String {
        val shown = if (n >= 1_000_000) String.format("%.2fM", n / 1_000_000.0)
        else "${(n / 1024).coerceAtLeast(0)}K"
        return s(R.string.provider_tokens_about, shown)
    }

    /**
     * 图2《新增模型（拉取后）》：只列尚未新增的候选、默认零勾选、可搜索、
     * 「全选/清空」由用户主动点、底部「选择要新增的模型 (N)」未勾选时置灰。
     */
    private fun showAddModelPicker(remoteIds: List<String>, alias: String) {
        val p = providers.firstOrNull { it.alias == alias }
            ?: current?.takeIf { it.alias == alias } ?: return
        val pending = ModelConfig.pendingModelIds(remoteIds, p.models.map { it.id }.toSet())
        if (pending.isEmpty()) {
            toast(s(R.string.provider_add_models_none))
            return
        }
        val checked = LinkedHashSet<String>()
        var query = ""

        EtaSheet.show(this, s(R.string.provider_add_models_title)) { sheet ->
            // 副标题 + 右上「全选 / 清空」
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(this).apply {
                text = s(R.string.provider_add_models_pending, pending.size)
                textSize = 14f
                setTextColor(Color.parseColor(EtaUi.SUB))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val selectAll = TextView(this).apply {
                text = s(R.string.provider_add_models_all)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#FFFFFF"))
                background = EtaUi.rounded(EtaUi.ACCENT, 16)
                setPadding(dp(18), dp(9), dp(18), dp(9))
            }
            header.addView(selectAll)
            sheet.body.addView(header, matchWidth().apply { bottomMargin = dp(12) })

            val listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            // 底部主按钮先建好，文字与可用态由 refreshConfirm() 统一刷新。
            val confirm = EtaSheet.button(
                this, s(R.string.provider_add_models_button_hint), filled = true, enabled = false,
            )

            fun refreshConfirm() {
                val n = checked.size
                EtaSheet.setButtonEnabled(
                    this, confirm, n > 0,
                    if (n > 0) s(R.string.provider_add_models_button, n)
                    else s(R.string.provider_add_models_button_hint),
                )
                selectAll.text = s(
                    if (n == pending.size) R.string.provider_add_models_clear
                    else R.string.provider_add_models_all,
                )
            }

            fun renderRows() {
                listHost.removeAllViews()
                val shown = pending.filter {
                    query.isBlank() || it.contains(query, ignoreCase = true)
                }
                shown.forEachIndexed { i, id ->
                    if (i > 0) listHost.addView(EtaUi.divider(this))
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(4), dp(16), dp(4), dp(16))
                        isClickable = true
                        setOnClickListener {
                            if (!checked.add(id)) checked.remove(id)
                            renderRows()
                            refreshConfirm()
                        }
                        EtaMotion.press(this@ProviderActivity, this)
                    }
                    row.addView(TextView(this).apply {
                        text = id
                        textSize = 17f
                        setTextColor(Color.parseColor(EtaUi.TEXT))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    row.addView(EtaUi.radio(this, checked.contains(id)))
                    listHost.addView(row)
                }
                if (shown.isEmpty()) {
                    listHost.addView(TextView(this).apply {
                        text = s(R.string.provider_model_empty)
                        textSize = 13f
                        setTextColor(Color.parseColor(EtaUi.MUTED))
                        setPadding(dp(4), dp(14), dp(4), dp(14))
                    })
                }
            }

            val search = EtaUi.searchBox(this, s(R.string.provider_add_models_search)) { q ->
                query = q.trim()
                renderRows()
            }
            sheet.body.addView(search, matchWidth().apply { bottomMargin = dp(8) })

            val scroller = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                addView(listHost, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }
            sheet.body.addView(scroller, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ))

            selectAll.setOnClickListener {
                val visible = pending.filter {
                    query.isBlank() || it.contains(query, ignoreCase = true)
                }
                if (checked.size == pending.size) checked.clear()
                else visible.forEach { checked.add(it) }
                renderRows()
                refreshConfirm()
                EtaMotion.pop(this, selectAll)
            }

            sheet.footer.addView(confirm, matchWidth())
            confirm.setOnClickListener {
                val picked = checked.toList()
                if (picked.isEmpty() || !confirm.isEnabled) return@setOnClickListener
                val fresh = picked.map { id ->
                    ModelSpec(
                        id = id,
                        contextSize = ModelCatalog.describe(id).contextSize,
                        reasoning = id.contains("reasoner") ||
                            id.contains("thinking", ignoreCase = true),
                        source = ProviderStore.SOURCE_REMOTE,
                    )
                }
                updateCurrent { cur -> cur.copy(models = cur.models + fresh) }
                persistAndNotify(restart = false)
                sheet.dismiss()
                renderModelList()
                toast(s(R.string.provider_add_models_added, picked.size))
            }
            refreshConfirm()
            renderRows()
        }
    }

    /** 图3《新增自订模型》：展示名称默认「自订模型」、Model ID、内容长度 +
     * 胶囊快捷、支援思考开关、取消/储存（未填 Model ID 时置灰）。 */
    private fun addCustomModel() {
        EtaSheet.show(this, s(R.string.provider_add_model_title)) { sheet ->
            val (nameBox, nameInput) = EtaSheet.field(
                this, s(R.string.provider_field_display_name),
                s(R.string.provider_custom_default_name),
            )
            sheet.body.addView(nameBox, matchWidth().apply { bottomMargin = dp(12) })

            val (idBox, idInput) = EtaSheet.field(this, s(R.string.provider_field_model_id), "", "gpt-4o")
            sheet.body.addView(idBox, matchWidth().apply { bottomMargin = dp(12) })

            val (ctxBox, ctxInput) = EtaSheet.field(
                this, s(R.string.provider_field_context_tokens), "",
                "1048576", numeric = true,
            )
            sheet.body.addView(ctxBox, matchWidth())

            val chips = EtaSheet.chipBar(this, CONTEXT_PRESETS.map { it.first }) { i, _ ->
                ctxInput.setText(CONTEXT_PRESETS[i].second.toString())
                ctxInput.setSelection(ctxInput.text.length)
            }
            sheet.body.addView(chips.view, matchWidth())
            sheet.body.addView(EtaSheet.hint(this, s(R.string.provider_context_custom_hint)))

            val (thinkRow, thinkSw) = EtaSheet.switchRow(
                this, s(R.string.provider_thinking), s(R.string.provider_thinking_auto), false,
            )
            sheet.body.addView(thinkRow, matchWidth())
            sheet.body.addView(EtaSheet.hint(this, s(R.string.provider_effort_note)))

            val (row, confirm) = EtaSheet.dualButtons(
                this, s(R.string.provider_btn_cancel), s(R.string.provider_btn_save),
                confirmEnabled = false,
                onCancel = { sheet.dismiss() }, onConfirm = {},
            )
            sheet.footer.addView(row)

            fun refresh() {
                val ok = idInput.text.toString().trim().isNotBlank()
                EtaSheet.setButtonEnabled(this, confirm, ok)
            }
            idInput.addTextChangedListener(simpleWatcher { refresh() })
            ctxInput.addTextChangedListener(simpleWatcher {
                val v = ctxInput.text.toString().trim().toIntOrNull()
                val idx = CONTEXT_PRESETS.indexOfFirst { it.second == v }
                if (idx >= 0) chips.select(idx) else chips.clear()
            })
            confirm.setOnClickListener {
                val modelId = idInput.text.toString().trim()
                val p = current ?: return@setOnClickListener
                if (modelId.isBlank()) return@setOnClickListener
                if (p.models.any { it.id.equals(modelId, ignoreCase = true) }) {
                    toast(s(R.string.provider_model_exists))
                    return@setOnClickListener
                }
                val m = ModelSpec(
                    id = modelId,
                    displayName = nameInput.text.toString().trim(),
                    contextSize = ctxInput.text.toString().trim().toIntOrNull()
                        ?: ModelCatalog.contextSizeFor(modelId),
                    reasoning = thinkSw.isChecked,
                    source = ProviderStore.SOURCE_MANUAL,
                )
                updateCurrent { it.copy(models = it.models + m) }
                persistAndNotify(restart = false)
                sheet.dismiss()
                renderModelList()
                toast(s(R.string.provider_model_added, modelId))
            }
            refresh()
        }
    }

    /** 图1《编辑模型》：展示名称 / Model ID 只读 / 内容长度 + 胶囊 + 约 X tokens /
     * 支援思考开关 / Off~Max 档位 / 恢复自动 · 删除模型 / 取消 · 储存。 */
    private fun editModel(p: ProviderSpec, m: ModelSpec) {
        EtaSheet.show(this, s(R.string.provider_edit_model_title)) { sheet ->
            val (nameBox, nameInput) = EtaSheet.field(
                this, s(R.string.provider_field_display_name), m.label,
            )
            sheet.body.addView(nameBox, matchWidth().apply { bottomMargin = dp(12) })

            val (idBox, _) = EtaSheet.field(
                this, s(R.string.provider_field_model_id), m.id, readOnly = true,
            )
            sheet.body.addView(idBox, matchWidth().apply { bottomMargin = dp(12) })

            val (ctxBox, ctxInput) = EtaSheet.field(
                this, s(R.string.provider_field_context_tokens), m.contextSize.toString(),
                numeric = true,
            )
            sheet.body.addView(ctxBox, matchWidth())
            val tokensHint = EtaSheet.hint(this, tokensText(m.contextSize))
            sheet.body.addView(tokensHint)

            val chips = EtaSheet.chipBar(this, CONTEXT_PRESETS.map { it.first }) { i, _ ->
                ctxInput.setText(CONTEXT_PRESETS[i].second.toString())
                ctxInput.setSelection(ctxInput.text.length)
                tokensHint.text = tokensText(CONTEXT_PRESETS[i].second)
            }
            val initCtx = CONTEXT_PRESETS.indexOfFirst { it.second == m.contextSize }
            if (initCtx >= 0) chips.select(initCtx)
            sheet.body.addView(chips.view, matchWidth())

            val (thinkRow, thinkSw) = EtaSheet.switchRow(
                this, s(R.string.provider_thinking), s(R.string.provider_thinking_override),
                m.reasoning,
            )
            sheet.body.addView(thinkRow, matchWidth())

            val effortChips = EtaSheet.chipBar(
                this, EFFORT_LEVELS, initial = EFFORT_LEVELS.indexOfFirst {
                    it.equals(m.effort, ignoreCase = true)
                }, perRow = 4,
            ) { _, _ -> }
            sheet.body.addView(effortChips.view, matchWidth())
            sheet.body.addView(EtaSheet.hint(this, s(R.string.provider_effort_note)))

            val links = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            links.addView(EtaSheet.link(this, s(R.string.provider_restore_auto), danger = false) {
                effortChips.clear()
                thinkSw.isChecked = ModelCatalog.describe(m.id).id.contains("reasoner") ||
                    m.id.contains("thinking", ignoreCase = true)
                toast(s(R.string.provider_restored_auto))
            })
            links.addView(EtaSheet.link(this, s(R.string.provider_remove_model_btn), danger = true) {
                updateCurrent { cur -> cur.copy(models = cur.models.filter { it.id != m.id }) }
                persistAndNotify(restart = false)
                sheet.dismiss()
                renderModelList()
                toast(s(R.string.provider_remove_model_note))
            })
            sheet.body.addView(links, matchWidth())

            val (row, _) = EtaSheet.dualButtons(
                this, s(R.string.provider_btn_cancel), s(R.string.provider_btn_save),
                onCancel = { sheet.dismiss() },
                onConfirm = {},
            )
            sheet.footer.addView(row)
            val confirm = (row as LinearLayout).getChildAt(1) as TextView

            ctxInput.addTextChangedListener(simpleWatcher {
                val v = ctxInput.text.toString().trim().toIntOrNull()
                tokensHint.text = tokensText(v ?: m.contextSize)
                val idx = CONTEXT_PRESETS.indexOfFirst { it.second == v }
                if (idx >= 0) chips.select(idx) else chips.clear()
            })

            confirm.setOnClickListener {
                val updated = m.copy(
                    displayName = nameInput.text.toString().trim(),
                    contextSize = ctxInput.text.toString().trim().toIntOrNull() ?: m.contextSize,
                    effort = if (effortChips.selected >= 0)
                        EFFORT_LEVELS[effortChips.selected].lowercase() else "",
                    reasoning = thinkSw.isChecked,
                )
                updateCurrent { cur ->
                    cur.copy(models = cur.models.map { if (it.id == m.id) updated else it })
                }
                // 逐模型参数（含 display_name）写进 config.toml
                persistAndNotify(restart = false)
                sheet.dismiss()
                renderModelList()
            }
        }
    }

    private fun simpleWatcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChange()
    }

    // ------------------------------------------------------------- 落盘

    /**
     * 草稿态的编辑只改内存里的 current，绝不写盘；这样「点进去看一眼直接返回」
     * 不会留下任何记录。只有设定页「保存」把草稿加入 providers 后，落盘才生效。
     */
    private fun updateCurrent(block: (ProviderSpec) -> ProviderSpec) {
        val p = current ?: return
        if (isDraft) {
            current = block(p)
            titleView.text = current!!.displayTitle
            return
        }
        val idx = providers.indexOfFirst { it.alias == p.alias }
        if (idx < 0) return
        providers[idx] = block(providers[idx])
        current = providers[idx]
        titleView.text = providers[idx].displayTitle
    }

    /** 保存到 ProviderStore + Prefs + config.toml，并按需要重启 / 刷新工作台。 */
    private fun persistAndNotify(restart: Boolean) {
        if (isDraft) return   // 草稿不落盘
        ProviderStore.save(this, providers)
        val entries = ProviderStore.toPrefsEntries(providers)
        Prefs.save(this, entries)
        if (entries.isNotEmpty()) {
            runCatching { ConfigWriter.write(this, entries, Prefs.activeEntry(this)) }
        }
        changed = true
        if (restart) RuntimeService.restart(this)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
