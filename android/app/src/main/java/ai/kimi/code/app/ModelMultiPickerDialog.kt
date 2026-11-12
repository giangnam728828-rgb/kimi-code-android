package ai.kimi.code.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * 「选择要添加的模型」—— 同一个服务商 / 分组里一次勾选多个模型。
 *
 * 之前的 [ModelPickerDialog] 是单选：点一个就关闭，只能给一个分组加一个
 * 模型。这里改成复选框多选 + 全选 + 搜索，点「添加所选」一次性把勾中的
 * 模型全部登记到当前分组；列表按模型商分区，不同分区之间用标题隔开。
 *
 * 与 [ModelPickerDialog] 一样全部用代码构建 View，不新增 XML id，
 * 不打乱资源表，也不依赖任何第三方库。
 */
object ModelMultiPickerDialog {

    private const val TYPE_HEADER = 0
    private const val TYPE_ITEM = 1
    private const val SEARCH_DEBOUNCE_MS = 120L

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Item(val model: CatalogModel) : Row()
    }

    private class Holder(
        val root: LinearLayout,
        val check: CheckBox,
        val title: TextView,
        val sub: TextView,
    )

    fun show(
        activity: Activity,
        baseUrl: String,
        apiKey: String,
        type: String,
        already: List<String> = emptyList(),
        cached: List<String> = emptyList(),
        onCache: (List<String>) -> Unit = {},
        onPicked: (List<CatalogModel>) -> Unit,
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        var ids: List<String> = cached
        var described: List<CatalogModel> = ModelCatalog.describeAll(ids)
        val rows = ArrayList<Row>()
        val checked = LinkedHashSet<String>()
        val registered = already.map { it.trim().lowercase() }.toSet()
        var dialog: AlertDialog? = null

        val status = TextView(activity).apply {
            text = activity.getString(R.string.model_multi_loading)
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, dp(6))
        }

        val hintLabel = TextView(activity).apply {
            text = activity.getString(R.string.model_multi_hint)
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }

        val search = EditText(activity).apply {
            this.hint = activity.getString(R.string.model_multi_search_hint)
            textSize = 14f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        fun smallButton(label: String): TextView = TextView(activity).apply {
            text = label
            textSize = 13f
            setGravity(Gravity.CENTER)
            setTextColor(activity.getColor(R.color.accent))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        val selectAllButton = smallButton(activity.getString(R.string.model_multi_select_all))
        val refreshButton = smallButton(activity.getString(R.string.model_multi_refresh))
        val manualButton = smallButton(activity.getString(R.string.model_multi_manual))

        val list = ListView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340))
            divider = null
        }

        fun updateStatus() {
            status.text = if (ids.isEmpty()) {
                activity.getString(R.string.model_multi_loading)
            } else {
                activity.getString(R.string.model_multi_selected, checked.size, ids.size)
            }
        }

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(position: Int): Any = rows[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getViewTypeCount(): Int = 2
            override fun getItemViewType(position: Int): Int =
                if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM
            override fun areAllItemsEnabled(): Boolean = false
            override fun isEnabled(position: Int): Boolean = rows[position] is Row.Item

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
                when (val row = rows[position]) {
                    is Row.Header -> {
                        val view = convertView as? TextView ?: TextView(activity).apply {
                            textSize = 11f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(activity.getColor(R.color.text_muted))
                            setBackgroundColor(Color.parseColor("#F2F3F5"))
                            setPadding(dp(12), dp(9), dp(12), dp(9))
                        }
                        view.text = row.title
                        view
                    }
                    is Row.Item -> {
                        val holder = (convertView?.tag as? Holder) ?: run {
                            val check = CheckBox(activity)
                            val title = TextView(activity).apply { textSize = 14f }
                            val sub = TextView(activity).apply {
                                textSize = 11f
                                setTextColor(activity.getColor(R.color.text_muted))
                            }
                            val column = LinearLayout(activity).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(title)
                                addView(sub)
                            }
                            val root = LinearLayout(activity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                setGravity(Gravity.CENTER_VERTICAL)
                                setPadding(dp(10), dp(8), dp(14), dp(8))
                                addView(check)
                                addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                            }
                            Holder(root, check, title, sub).also { root.tag = it }
                        }
                        val model = row.model
                        val isRegistered = registered.contains(model.id.trim().lowercase())
                        holder.check.setOnCheckedChangeListener(null)
                        holder.check.isEnabled = !isRegistered
                        holder.check.isChecked = isRegistered || checked.contains(model.id)
                        holder.check.setOnCheckedChangeListener { _, isChecked ->
                            if (isChecked) checked.add(model.id) else checked.remove(model.id)
                            updateStatus()
                        }
                        holder.title.text = model.id
                        holder.title.setTextColor(
                            activity.getColor(if (isRegistered) R.color.text_muted else R.color.text_primary),
                        )
                        holder.sub.text = if (isRegistered) {
                            activity.getString(R.string.model_multi_already)
                        } else {
                            "${model.vendor} · 上下文 ${formatContext(model.contextSize)}"
                        }
                        holder.root.setOnClickListener {
                            if (holder.check.isEnabled) holder.check.performClick()
                        }
                        holder.root
                    }
                }
        }

        fun render() {
            val q = search.text.toString().trim()
            rows.clear()
            if (q.isEmpty()) {
                for ((label, items) in ModelCatalog.group(described)) {
                    rows.add(Row.Header("$label  (${items.size})"))
                    items.forEach { rows.add(Row.Item(it)) }
                }
            } else {
                val hits = ModelCatalog.search(described, q)
                rows.add(Row.Header(activity.getString(R.string.model_multi_search_result, hits.size)))
                hits.forEach { rows.add(Row.Item(it)) }
            }
            adapter.notifyDataSetChanged()
            updateStatus()
        }

        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val item = rows.getOrNull(position) as? Row.Item ?: return@setOnItemClickListener
            if (item.model.id.trim().lowercase() in registered) return@setOnItemClickListener
            if (checked.contains(item.model.id)) checked.remove(item.model.id) else checked.add(item.model.id)
            adapter.notifyDataSetChanged()
            updateStatus()
        }

        selectAllButton.setOnClickListener {
            val selectable = rows.filterIsInstance<Row.Item>()
                .map { it.model.id }
                .filter { it.trim().lowercase() !in registered }
            val allChecked = selectable.isNotEmpty() && selectable.all { checked.contains(it) }
            if (allChecked) checked.clear() else checked.addAll(selectable)
            selectAllButton.text = activity.getString(
                if (allChecked) R.string.model_multi_select_all else R.string.model_multi_clear,
            )
            adapter.notifyDataSetChanged()
            updateStatus()
        }

        manualButton.setOnClickListener {
            val input = EditText(activity).apply {
                this.hint = activity.getString(R.string.model_multi_manual_hint)
                textSize = 14f
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            val box = LinearLayout(activity).apply {
                setPadding(dp(20), dp(8), dp(20), dp(8))
                addView(input, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }
            AlertDialog.Builder(activity)
                .setTitle(R.string.model_multi_manual)
                .setView(box)
                .setPositiveButton(R.string.model_multi_manual_add) { _, _ ->
                    val id = input.text.toString().trim()
                    if (id.isEmpty()) return@setPositiveButton
                    if (ids.none { it.equals(id, ignoreCase = true) }) {
                        ids = ids + id
                        described = ModelCatalog.describeAll(ids)
                    }
                    checked.add(id)
                    render()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        val searchHandler = Handler(Looper.getMainLooper())
        var pendingSearch: Runnable? = null
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                pendingSearch?.let { searchHandler.removeCallbacks(it) }
                val task = Runnable { render() }
                pendingSearch = task
                searchHandler.postDelayed(task, SEARCH_DEBOUNCE_MS)
            }
        })

        fun loadInternal(force: Boolean) {
            if (!force && ids.isNotEmpty()) {
                status.text = activity.getString(R.string.model_multi_cached, ids.size)
                render()
                return
            }
            status.text = activity.getString(R.string.model_multi_loading)
            rows.clear()
            adapter.notifyDataSetChanged()
            Thread {
                val result = ModelCatalog.fetch(baseUrl, apiKey, type)
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if (result.error != null) {
                        status.setTextColor(activity.getColor(R.color.warn))
                        status.text = "${result.error} · 可用「手动输入」"
                        if (ids.isNotEmpty()) render()
                    } else {
                        ids = result.ids
                        described = ModelCatalog.describeAll(result.ids)
                        onCache(result.ids)
                        status.setTextColor(activity.getColor(R.color.text_secondary))
                        render()
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        // Kotlin 局部函数不能前向引用，这里用一个显式变量桥接按钮回调。
        // 声明顺序：先定义 loadInternal，再把它交给按钮的 lambda。
        val loadRef = { force: Boolean -> loadInternal(force) }
        refreshButton.setOnClickListener { loadRef(true) }

        val toolRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(Gravity.CENTER_VERTICAL)
            addView(selectAllButton)
            addView(refreshButton)
            addView(manualButton)
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(hintLabel)
            addView(status)
            addView(search, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(toolRow)
            addView(list)
        }

        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.model_multi_title)
            .setView(root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.model_multi_add, null)
            .show()
        dialog?.setOnDismissListener {
            pendingSearch?.let { searchHandler.removeCallbacks(it) }
        }

        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            if (checked.isEmpty()) {
                Toast.makeText(activity, R.string.model_multi_none, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 保持目录里的原始顺序，而不是勾选顺序，方便用户核对。
            val picked = described.filter { checked.contains(it.id) }
            dialog?.dismiss()
            onPicked(picked)
        }

        loadRef(false)
    }

    private fun formatContext(size: Int): String = when {
        size >= 1024 * 1024 -> "${size / (1024 * 1024)}M"
        else -> "${size / 1024}K"
    }
}
