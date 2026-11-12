package ai.kimi.code.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
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
 * Eta 风格的「模型供应商」两级管理页。
 *
 * 一级：供应商列表（搜索 / 从目录添加 / 新增三类 / 已设定供应商带单选）。
 * 二级：点进某个供应商，「设定 / 模型」双 Tab —— 设定页填名称、Base URL、
 *       API Key、Endpoint 模式、测试连线、自定义请求头、启用开关；模型页支持
 *       从远端 /models 拉取、新增自订模型、搜索，且每个模型可单独设置。
 *
 * 配置只在点「保存」时一次性落盘，切换默认模型走 ConfigWriter.setDefaultModel
 * 的轻量热更新，不重启运行环境 —— 这是本版流畅性的关键。
 */
class ProviderActivity : Activity() {

    companion object {
        /** MainActivity.onResume 据此刷新界面与网页模型列表。 */
        @JvmStatic
        var changed = false
    }

    private lateinit var titleView: TextView
    private lateinit var pageHost: FrameLayout
    private var providers: MutableList<ProviderSpec> = mutableListOf()
    private var current: ProviderSpec? = null
    private var detailTab = 0
    private var searchText = ""
    private var busy = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
        })
        titleView = TextView(this).apply {
            text = getString(R.string.provider_title)
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
    }

    private fun onBack() {
        if (current != null) showList() else finish()
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
    }

    // ------------------------------------------------------------- 一级：列表

    private fun showList() {
        current = null
        searchText = ""
        titleView.text = getString(R.string.provider_title)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(EtaUi.bigTitle(this, getString(R.string.provider_title)))
        c.addView(EtaUi.searchBox(this, getString(R.string.provider_search)) { q ->
            searchText = q.trim()
            renderList(c)
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

    private fun renderList(root: LinearLayout) = renderListBody()

    private fun renderListBody() {
        val body = listBody ?: return
        body.removeAllViews()

        // 新增供应商
        body.addView(EtaUi.sectionLabel(this, getString(R.string.provider_add_section)))
        val addCard = EtaUi.card(this)
        addCard.addView(EtaUi.actionRow(
            this, "▦", getString(R.string.provider_from_catalog),
            getString(R.string.provider_from_catalog_sub),
            { openCatalog() },
        ))
        addCard.addView(EtaUi.divider(this))
        addCard.addView(EtaUi.actionRow(
            this, "◎", getString(R.string.provider_add_openai),
            getString(R.string.provider_add_openai_sub),
            { addProvider(ProviderStore.TYPE_OPENAI) }, "#EDE7FB", "#6D4BC3",
        ))
        addCard.addView(EtaUi.divider(this))
        addCard.addView(EtaUi.actionRow(
            this, "A", getString(R.string.provider_add_anthropic),
            getString(R.string.provider_add_anthropic_sub),
            { addProvider(ProviderStore.TYPE_ANTHROPIC) }, "#FBEDE4", "#B4602A",
        ))
        addCard.addView(EtaUi.divider(this))
        addCard.addView(EtaUi.actionRow(
            this, "✦", getString(R.string.provider_add_gemini),
            getString(R.string.provider_add_gemini_sub),
            { addProvider(ProviderStore.TYPE_GEMINI) }, "#E5F3EA", "#1F8A4C",
        ))
        body.addView(addCard, matchWidth().apply { bottomMargin = dp(6) })

        // 已设定供应商
        val q = searchText.lowercase()
        val shown = providers.filter {
            q.isBlank() || it.displayTitle.lowercase().contains(q) ||
                it.baseUrl.lowercase().contains(q) ||
                it.usableModels.any { m -> m.id.lowercase().contains(q) }
        }
        body.addView(EtaUi.sectionLabel(
            this, getString(R.string.provider_configured, providers.size),
        ))
        if (shown.isEmpty()) {
            body.addView(TextView(this).apply {
                text = getString(R.string.provider_empty)
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
        }

        // 2.16.1：旧设置页被替换后，把几个 App 级开关搬到这里，功能不丢失。
        body.addView(EtaUi.sectionLabel(this, getString(R.string.provider_app_prefs)))
        val prefCard = EtaUi.card(this, 18)
        prefCard.addView(prefSwitch(
            getString(R.string.provider_pref_swipe), Prefs.swipeBack(this),
        ) { Prefs.setSwipeBack(this, it) })
        prefCard.addView(EtaUi.divider(this))
        prefCard.addView(prefSwitch(
            getString(R.string.provider_pref_lan), Prefs.lan(this),
        ) {
            Prefs.setLan(this, it)
            toast(getString(R.string.provider_pref_restart_hint))
        })
        prefCard.addView(EtaUi.divider(this))
        prefCard.addView(prefSwitch(
            getString(R.string.provider_pref_awake), Prefs.keepAwake(this),
        ) { Prefs.setKeepAwake(this, it) })
        body.addView(prefCard, matchWidth())
        body.addView(TextView(this).apply {
            text = getString(R.string.provider_no_auto_enter)
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
        }

    private fun providerRow(p: ProviderSpec): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setOnClickListener { openDetail(p.alias) }
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
                text = p.baseUrl.ifBlank { getString(R.string.provider_no_url) }
                textSize = 12f
                setTextColor(Color.parseColor(EtaUi.SUB))
                setPadding(0, dp(3), 0, dp(2))
            })
            addView(TextView(this@ProviderActivity).apply {
                text = buildString {
                    append(ProviderStore.typeLabel(p.type))
                    append(" · ")
                    append(getString(R.string.provider_model_count, p.usableModels.size))
                    if (!p.enabled) append(" · ").append(getString(R.string.provider_disabled))
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

    private fun openCatalog() {
        val entries = ProviderStore.CATALOG
        AlertDialog.Builder(this)
            .setTitle(R.string.provider_from_catalog)
            .setItems(entries.map { "${it.name}  ·  ${it.hint}" }.toTypedArray()) { _, which ->
                val e = entries[which]
                addProvider(e.type, e)
            }
            .show()
    }

    private fun addProvider(type: String, preset: ProviderStore.CatalogEntry? = null) {
        val used = providers.map { it.alias }.toSet()
        val base = preset?.key ?: when (type) {
            ProviderStore.TYPE_ANTHROPIC -> "anthropic"
            ProviderStore.TYPE_GEMINI -> "gemini"
            else -> "custom"
        }
        val alias = ProviderStore.uniqueAlias(base, used)
        val p = ProviderSpec(
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
        providers.add(p)
        ProviderStore.save(this, providers)
        openDetail(alias)
    }

    // ------------------------------------------------------------- 二级：详情

    private fun openDetail(alias: String) {
        current = providers.firstOrNull { it.alias == alias } ?: return
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
            listOf(getString(R.string.provider_tab_settings), getString(R.string.provider_tab_models)),
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
    }

    // ------------------------------------------------------------- 设定 Tab

    private fun renderSettingsTab() {
        val body = detailBody ?: return
        body.removeAllViews()
        val p = current ?: return

        body.addView(EtaUi.sectionLabel(this, getString(R.string.provider_connection)))
        val card = EtaUi.card(this, 18)

        val (nameBox, nameInput) = EtaUi.fieldBox(
            this, getString(R.string.provider_field_name), p.name,
            getString(R.string.provider_field_name_hint),
        )
        card.addView(nameBox, matchWidth().apply { bottomMargin = dp(10) })

        val (urlBox, urlInput) = EtaUi.fieldBox(
            this, "Base URL", p.baseUrl, "https://api.example.com/v1",
        )
        card.addView(urlBox, matchWidth().apply { bottomMargin = dp(10) })

        // API Key 带显隐切换
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
            this, getString(R.string.provider_endpoint),
            ProviderStore.endpointLabel(p.endpointMode),
        ) { chooseEndpoint() })
        card.addView(EtaUi.divider(this))
        card.addView(EtaUi.linkRow(
            this, getString(R.string.provider_test), "",
        ) { probeConnection() })
        card.addView(EtaUi.divider(this))
        card.addView(EtaUi.linkRow(
            this, getString(R.string.provider_headers),
            if (p.customHeaders.isBlank()) getString(R.string.provider_headers_unset)
            else getString(R.string.provider_headers_set),
        ) { editHeaders() })
        body.addView(card, matchWidth())

        body.addView(EtaUi.sectionLabel(this, getString(R.string.provider_preference)))
        val prefCard = EtaUi.card(this, 18)
        val sw = Switch(this).apply {
            text = getString(R.string.provider_enable)
            textSize = 16f
            isChecked = p.enabled
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnCheckedChangeListener { _, v -> updateCurrent { it.copy(enabled = v) } }
        }
        prefCard.addView(sw)
        body.addView(prefCard, matchWidth())

        body.addView(EtaUi.primaryButton(this, getString(R.string.provider_save)) {
            val name = nameInput.text.toString().trim()
            val url = urlInput.text.toString().trim()
            val key = keyInput.text.toString().trim()
            updateCurrent {
                it.copy(
                    name = name.ifBlank { it.alias },
                    baseUrl = url,
                    apiKey = key,
                )
            }
            persistAndNotify(restart = true)
            toast(getString(R.string.provider_saved))
        }, matchWidth().apply { topMargin = dp(22) })

        body.addView(TextView(this).apply {
            text = getString(R.string.provider_delete)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#DC2626"))
            setPadding(dp(10), dp(22), dp(10), dp(10))
            setOnClickListener { confirmDelete() }
        }, matchWidth())
    }

    private fun chooseEndpoint() {
        val modes = listOf(
            ProviderStore.ENDPOINT_CHAT,
            ProviderStore.ENDPOINT_RESPONSES,
            ProviderStore.ENDPOINT_MESSAGES,
            ProviderStore.ENDPOINT_INTERACTIONS,
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.provider_endpoint)
            .setItems(modes.map { ProviderStore.endpointLabel(it) }.toTypedArray()) { _, w ->
                updateCurrent { it.copy(endpointMode = modes[w]) }
                renderDetailBody()
            }
            .show()
    }

    private fun probeConnection() {
        val p = current ?: return
        val model = p.effectiveDefault
        if (p.baseUrl.isBlank() || p.apiKey.isBlank() || model.isBlank()) {
            toast(getString(R.string.provider_need_fields))
            return
        }
        toast(getString(R.string.provider_testing))
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
        val input = EditText(this).apply {
            setText(p.customHeaders)
            hint = getString(R.string.provider_headers_hint)
            textSize = 14f
            setPadding(dp(20), dp(12), dp(20), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.provider_headers)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                updateCurrent { it.copy(customHeaders = input.text.toString().trim()) }
                renderDetailBody()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDelete() {
        val p = current ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.provider_delete_confirm, p.displayTitle))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                providers.removeAll { it.alias == p.alias }
                ProviderStore.save(this, providers)
                changed = true
                showList()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ------------------------------------------------------------- 模型 Tab

    private fun renderModelsTab() {
        val body = detailBody ?: return
        body.removeAllViews()
        val p = current ?: return

        body.addView(EtaUi.sectionLabel(this, getString(R.string.provider_model_manage)))
        val manageCard = EtaUi.card(this, 18)
        manageCard.addView(EtaUi.actionRow(
            this, "⇩", getString(R.string.provider_fetch_remote),
            getString(R.string.provider_fetch_remote_sub, p.baseUrl.ifBlank { "Base URL" }),
            { fetchRemote() },
        ))
        manageCard.addView(EtaUi.divider(this))
        manageCard.addView(EtaUi.actionRow(
            this, "+", getString(R.string.provider_add_custom_model),
            getString(R.string.provider_add_custom_model_sub),
            { addCustomModel() },
        ))
        body.addView(manageCard, matchWidth().apply { bottomMargin = dp(4) })

        body.addView(EtaUi.searchBox(this, getString(R.string.provider_model_search)) { q ->
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
        host.addView(EtaUi.sectionLabel(
            this, getString(R.string.provider_model_list, p.usableModels.size),
        ))
        host.addView(TextView(this).apply {
            text = getString(R.string.provider_model_row_hint)
            textSize = 12f
            setTextColor(Color.parseColor(EtaUi.MUTED))
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        if (shown.isEmpty()) {
            host.addView(TextView(this).apply {
                text = getString(R.string.provider_model_empty)
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
        }
    }

    private fun modelRow(p: ProviderSpec, m: ProviderStore.ModelSpec): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(12), dp(16))
            // 2.16.1：整行就是「单独设置」入口，不再用那个点不准的小齿轮键。
            // 右侧单选圈有自己的 OnClickListener，会消费点击，不会误触整行。
            isClickable = true
            setOnClickListener { editModel(p, m) }
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
                    getString(R.string.provider_context, m.contextSize / 1024)))
                if (m.reasoning) tags.addView(
                    EtaUi.chip(this@ProviderActivity, getString(R.string.provider_reasoning)),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { leftMargin = dp(6) },
                )
                addView(tags)
            }
            addView(info)
            // 2.16.1：设置入口已改为「点整行」，这里不再放小图标按钮。
            // 单选：设为该分组默认模型
            addView(EtaUi.radio(this@ProviderActivity, m.id == p.effectiveDefault).apply {
                setOnClickListener {
                    updateCurrent { it.copy(defaultModel = m.id) }
                    // 轻量热更新：只改 default_model 一行，不重启
                    val key = ConfigMerger.modelKey(p.alias, m.id)
                    runCatching { ConfigWriter.setDefaultModel(this@ProviderActivity, key, m.contextSize) }
                    persistAndNotify(restart = false)
                    renderModelList()
                    toast(getString(R.string.provider_default_set, m.label))
                }
            })
        }

    private fun fetchRemote() {
        val p = current ?: return
        if (busy) return
        if (p.baseUrl.isBlank() || p.apiKey.isBlank()) {
            toast(getString(R.string.provider_need_fields))
            return
        }
        busy = true
        toast(getString(R.string.provider_fetching))
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
                    toast(r.error ?: getString(R.string.provider_fetch_failed))
                    return@runOnUiThread
                }
                // 2.16.1：拉取只负责把候选列出来，绝不自动加入。用户勾选确认
                // 之后才写进模型清单（对照用户提供的《新增模型》截图）。
                showAddModelPicker(r.ids, alias)
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 2.16.1：「新增模型」勾选页。只列远端有、本分组还没加入的模型；默认一个
     * 都不勾；底部按钮未勾选时置灰不可点；「全选 / 清空」必须由用户主动点，
     * 因此不会再出现「一点拉取就全加进来，再一个一个删」。
     */
    private fun showAddModelPicker(remoteIds: List<String>, alias: String) {
        val p = providers.firstOrNull { it.alias == alias } ?: return
        // 只列「尚未新增」的候选，默认一个都不勾；纯 JVM 逻辑见 ModelConfig。
        val pending = ModelConfig.pendingModelIds(
            remoteIds, p.models.map { it.id }.toSet(),
        )
        if (pending.isEmpty()) {
            toast(getString(R.string.provider_add_models_none))
            return
        }
        val checked = LinkedHashSet<String>()
        var query = ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(EtaUi.BG))
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.provider_add_models_title)
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(EtaUi.TEXT))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val selectAll = TextView(this).apply {
            text = getString(R.string.provider_add_models_all)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFFFFF"))
            background = EtaUi.rounded(EtaUi.ACCENT, 14)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        header.addView(selectAll)
        root.addView(header, matchWidth())

        root.addView(TextView(this).apply {
            text = getString(R.string.provider_add_models_pending, pending.size)
            textSize = 13f
            setTextColor(Color.parseColor(EtaUi.SUB))
            setPadding(0, dp(8), 0, dp(12))
        }, matchWidth())

        val listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroller = ScrollView(this).apply {
            addView(listHost, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        val confirm = TextView(this).apply {
            text = getString(R.string.provider_add_models_button_hint)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFFFFF"))
            setPadding(dp(14), dp(15), dp(14), dp(15))
        }

        fun refreshConfirm() {
            val n = checked.size
            confirm.text = if (n > 0)
                getString(R.string.provider_add_models_button, n)
            else getString(R.string.provider_add_models_button_hint)
            confirm.isEnabled = n > 0
            confirm.background = EtaUi.rounded(
                if (n > 0) EtaUi.ACCENT else "#C7D6F5", 13,
            )
            selectAll.text = getString(
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
                    setPadding(dp(4), dp(14), dp(4), dp(14))
                    isClickable = true
                    setOnClickListener {
                        if (!checked.add(id)) checked.remove(id)
                        renderRows()
                        refreshConfirm()
                    }
                }
                row.addView(TextView(this).apply {
                    text = id
                    textSize = 16f
                    setTextColor(Color.parseColor(EtaUi.TEXT))
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                    )
                })
                row.addView(EtaUi.radio(this, checked.contains(id)))
                listHost.addView(row)
            }
            if (shown.isEmpty()) {
                listHost.addView(TextView(this).apply {
                    text = getString(R.string.provider_model_empty)
                    textSize = 13f
                    setTextColor(Color.parseColor(EtaUi.MUTED))
                    setPadding(dp(4), dp(14), dp(4), dp(14))
                })
            }
        }

        val search = EtaUi.searchBox(
            this, getString(R.string.provider_add_models_search),
        ) { q ->
            query = q.trim()
            renderRows()
        }
        root.addView(search, matchWidth().apply { bottomMargin = dp(8) })
        root.addView(scroller, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(260),
        ))
        selectAll.setOnClickListener {
            val visible = pending.filter {
                query.isBlank() || it.contains(query, ignoreCase = true)
            }
            if (checked.size == pending.size) checked.clear()
            else visible.forEach { checked.add(it) }
            renderRows()
            refreshConfirm()
        }
        root.addView(confirm, matchWidth().apply { topMargin = dp(14) })
        refreshConfirm()
        renderRows()

        val dialog = AlertDialog.Builder(this).setView(root).create()
        confirm.setOnClickListener {
            val picked = checked.toList()
            if (picked.isEmpty()) return@setOnClickListener
            val fresh = picked.map { id ->
                ProviderStore.ModelSpec(
                    id = id,
                    contextSize = ModelCatalog.describe(id).contextSize,
                    reasoning = id.contains("reasoner") ||
                        id.contains("thinking", ignoreCase = true),
                    source = ProviderStore.SOURCE_REMOTE,
                )
            }
            updateCurrent { cur -> cur.copy(models = cur.models + fresh) }
            persistAndNotify(restart = false)
            dialog.dismiss()
            renderModelList()
            toast(getString(R.string.provider_add_models_added, picked.size))
        }
        dialog.show()
    }

    private fun addCustomModel() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
        }
        val name = EditText(this).apply {
            hint = getString(R.string.provider_model_display_hint)
            textSize = 15f
        }
        val id = EditText(this).apply {
            hint = getString(R.string.provider_model_id_hint)
            textSize = 15f
        }
        layout.addView(name)
        layout.addView(id)
        AlertDialog.Builder(this)
            .setTitle(R.string.provider_add_custom_model)
            .setView(layout)
            .setPositiveButton(R.string.provider_add) { _, _ ->
                val modelId = id.text.toString().trim()
                if (modelId.isBlank()) return@setPositiveButton
                val p = current ?: return@setPositiveButton
                if (p.models.any { it.id.equals(modelId, ignoreCase = true) }) {
                    toast(getString(R.string.provider_model_exists))
                    return@setPositiveButton
                }
                val m = ProviderStore.ModelSpec(
                    id = modelId,
                    displayName = name.text.toString().trim(),
                    contextSize = ModelCatalog.contextSizeFor(modelId),
                    source = ProviderStore.SOURCE_MANUAL,
                )
                updateCurrent { it.copy(models = it.models + m) }
                ProviderStore.save(this, providers)
                changed = true
                renderModelList()
                toast(getString(R.string.provider_model_added, modelId))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun editModel(p: ProviderSpec, m: ProviderStore.ModelSpec) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
        }
        val name = EditText(this).apply {
            hint = getString(R.string.provider_model_display_hint)
            setText(m.displayName)
            textSize = 15f
        }
        val ctx = EditText(this).apply {
            hint = getString(R.string.provider_context_hint)
            setText(m.contextSize.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 15f
        }
        val effort = EditText(this).apply {
            hint = getString(R.string.provider_effort_hint)
            setText(m.effort)
            textSize = 15f
        }
        val reasoning = Switch(this).apply {
            text = getString(R.string.provider_reasoning)
            isChecked = m.reasoning
            setPadding(0, dp(10), 0, dp(10))
        }
        layout.addView(name)
        layout.addView(ctx)
        layout.addView(effort)
        layout.addView(reasoning)

        // 2.16.1：显眼的大号「设为默认模型」行，用户不必再去瞄右边小圆圈。
        var dialog: AlertDialog? = null
        val makeDefault = TextView(this).apply {
            text = if (m.id == p.effectiveDefault)
                getString(R.string.provider_default_already)
            else getString(R.string.provider_set_default)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(EtaUi.ACCENT))
            background = EtaUi.rounded(EtaUi.ACCENT_SOFT, 12)
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener {
                if (m.id != p.effectiveDefault) {
                    updateCurrent { it.copy(defaultModel = m.id) }
                    val key = ConfigMerger.modelKey(p.alias, m.id)
                    runCatching { ConfigWriter.setDefaultModel(this@ProviderActivity, key, m.contextSize) }
                    persistAndNotify(restart = false)
                    toast(getString(R.string.provider_default_set, m.label))
                } else {
                    toast(getString(R.string.provider_default_already))
                }
                dialog?.dismiss()
                renderModelList()
            }
        }
        layout.addView(makeDefault, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })

        dialog = AlertDialog.Builder(this)
            .setTitle(m.label)
            .setView(layout)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val updated = m.copy(
                    displayName = name.text.toString().trim(),
                    contextSize = ctx.text.toString().trim().toIntOrNull() ?: m.contextSize,
                    effort = effort.text.toString().trim(),
                    reasoning = reasoning.isChecked,
                )
                updateCurrent { cur ->
                    cur.copy(models = cur.models.map { if (it.id == m.id) updated else it })
                }
                // 逐模型参数要同步写进 config.toml，旧实现只存了 ProviderStore。
                persistAndNotify(restart = false)
                renderModelList()
            }
            .setNeutralButton(R.string.provider_remove_model) { _, _ ->
                updateCurrent { cur -> cur.copy(models = cur.models.filter { it.id != m.id }) }
                persistAndNotify(restart = false)
                renderModelList()
                toast(getString(R.string.provider_remove_model_note))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.show()
    }

    // ------------------------------------------------------------- 落盘

    private fun updateCurrent(block: (ProviderSpec) -> ProviderSpec) {
        val p = current ?: return
        val idx = providers.indexOfFirst { it.alias == p.alias }
        if (idx < 0) return
        providers[idx] = block(providers[idx])
        current = providers[idx]
        titleView.text = providers[idx].displayTitle
    }

    /**
     * 保存到 ProviderStore + Prefs，并按需要写 config.toml。
     * restart=false 只改 default_model 一行（切换模型，秒级、无重启）；
     * restart=true 用于新增/编辑供应商（模型段变化，需要服务端重读一次）。
     */
    private fun persistAndNotify(restart: Boolean) {
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
