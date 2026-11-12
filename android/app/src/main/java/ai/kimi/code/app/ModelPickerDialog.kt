package ai.kimi.code.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Searchable model picker, modelled on a provider catalogue rather than a
 * free-text field. It always shows the raw upstream ids so the value that is
 * saved is one the gateway actually routes (never a display name).
 *
 * The View is built in code on purpose: no new XML ids means no resource-table
 * churn, and the dialog stays self-contained.
 */
object ModelPickerDialog {

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Item(val model: CatalogModel) : Row()
    }

    fun show(
        activity: Activity,
        baseUrl: String,
        apiKey: String,
        type: String,
        current: String,
        cached: List<String>,
        onCache: (List<String>) -> Unit,
        onPicked: (CatalogModel) -> Unit,
    ) {
        val density = activity.resources.displayMetrics.density
        val dp: (Int) -> Int = { v -> (v * density).toInt() }

        var ids: List<String> = cached
        val rows = ArrayList<Row>()
        var dialog: AlertDialog? = null

        val status = TextView(activity).apply {
            text = "准备获取模型列表…"
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }

        val search = EditText(activity).apply {
            hint = "搜索模型名，如 kimi / claude / spark"
            textSize = 14f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        fun chip(label: String): TextView = TextView(activity).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(activity.getColor(R.color.accent))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#F2F3F5"))
                setStroke(1, activity.getColor(R.color.accent))
            }
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }

        val refreshButton = chip("刷新列表")
        val manualButton = chip("手动输入")
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            addView(refreshButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(6) })
            addView(manualButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(6) })
        }

        val list = ListView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(340),
            )
            dividerHeight = 0
        }

        val rowAdapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(position: Int): Any = rows[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun areAllItemsEnabled(): Boolean = false
            override fun isEnabled(position: Int): Boolean = rows[position] is Row.Item

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
                when (val row = rows[position]) {
                    is Row.Header -> TextView(activity).apply {
                        text = row.title
                        textSize = 11f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(activity.getColor(R.color.text_muted))
                        setBackgroundColor(Color.parseColor("#F2F3F5"))
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                    }
                    is Row.Item -> {
                        val m = row.model
                        val selected = m.id == current
                        LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(14), dp(9), dp(14), dp(9))
                            addView(TextView(activity).apply {
                                text = if (selected) "${m.id}   （当前）" else m.id
                                textSize = 14f
                                setTextColor(activity.getColor(R.color.text_primary))
                                if (selected) setTypeface(null, Typeface.BOLD)
                            })
                            addView(TextView(activity).apply {
                                text = "${m.vendor} · 上下文 ${formatContext(m.contextSize)}"
                                textSize = 11f
                                setTextColor(activity.getColor(R.color.text_muted))
                            })
                        }
                    }
                }
        }

        fun render() {
            val q = search.text.toString().trim()
            val models = ModelCatalog.describeAll(ids)
            rows.clear()
            if (q.isEmpty()) {
                for ((label, items) in ModelCatalog.group(models)) {
                    rows.add(Row.Header("$label  (${items.size})"))
                    items.forEach { rows.add(Row.Item(it)) }
                }
            } else {
                val hits = ModelCatalog.search(models, q)
                rows.add(Row.Header("搜索结果  (${hits.size})"))
                hits.forEach { rows.add(Row.Item(it)) }
            }
            rowAdapter.notifyDataSetChanged()
        }

        list.adapter = rowAdapter
        list.setOnItemClickListener { _, _, position, _ ->
            val row = rows.getOrNull(position)
            if (row is Row.Item) {
                dialog?.dismiss()
                onPicked(row.model)
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { render() }
        })

        fun load(force: Boolean) {
            if (!force && ids.isNotEmpty()) {
                status.setTextColor(activity.getColor(R.color.text_secondary))
                status.text = "已缓存 ${ids.size} 个模型 · 点「刷新列表」重新获取"
                render()
                return
            }
            status.setTextColor(activity.getColor(R.color.text_secondary))
            status.text = "正在从接口获取模型列表…"
            rows.clear()
            rowAdapter.notifyDataSetChanged()
            Thread {
                val result = ModelCatalog.fetch(baseUrl, apiKey, type)
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    if (result.error != null) {
                        status.setTextColor(activity.getColor(R.color.warn))
                        status.text = "${result.error} · 可点「手动输入」"
                        if (ids.isNotEmpty()) render()
                    } else {
                        ids = result.ids
                        onCache(result.ids)
                        status.setTextColor(activity.getColor(R.color.text_secondary))
                        status.text = "已获取 ${ids.size} 个模型 · 点一下即可填入"
                        render()
                    }
                }
            }.start()
        }

        refreshButton.setOnClickListener { load(true) }
        manualButton.setOnClickListener {
            dialog?.dismiss()
            askManual(activity, current, dp) { onPicked(it) }
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(status)
            addView(search, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(actions, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(list)
        }

        dialog = AlertDialog.Builder(activity)
            .setTitle("选择模型")
            .setView(root)
            .setNegativeButton("取消", null)
            .show()
        load(false)
    }

    /** Fallback for gateways that do not expose a `/models` endpoint. */
    private fun askManual(
        activity: Activity,
        current: String,
        dp: (Int) -> Int,
        onPicked: (CatalogModel) -> Unit,
    ) {
        val input = EditText(activity).apply {
            setText(current)
            setSelection(text.length)
            hint = "粘贴服务商文档里的 model id（区分大小写）"
            textSize = 14f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        AlertDialog.Builder(activity)
            .setTitle("手动输入模型名")
            .setMessage("请填写服务商文档给出的准确 id，而不是展示名。")
            .setView(box)
            .setPositiveButton("使用") { _, _ ->
                val id = input.text.toString().trim()
                if (id.isNotEmpty()) onPicked(ModelCatalog.describe(id))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formatContext(size: Int): String = when {
        size >= 1024 * 1024 -> "${size / (1024 * 1024)}M"
        else -> "${size / 1024}K"
    }
}
