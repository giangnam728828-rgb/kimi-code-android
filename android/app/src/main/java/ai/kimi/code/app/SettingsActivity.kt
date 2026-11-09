package ai.kimi.code.app

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView

/** Model provider / API key settings; saved straight into config.toml. */
class SettingsActivity : Activity() {

    private lateinit var spinner: Spinner
    private lateinit var editBaseUrl: EditText
    private lateinit var editApiKey: EditText
    private lateinit var editModel: EditText
    private lateinit var editContext: EditText
    private lateinit var saveHint: TextView
    private lateinit var checkAutoEnter: CheckBox

    private var selectedId: String = "kimi"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        spinner = findViewById(R.id.spinnerProvider)
        editBaseUrl = findViewById(R.id.editBaseUrl)
        editApiKey = findViewById(R.id.editApiKey)
        editModel = findViewById(R.id.editModel)
        editContext = findViewById(R.id.editContext)
        saveHint = findViewById(R.id.saveHint)
        checkAutoEnter = findViewById(R.id.checkAutoEnter)
        checkAutoEnter.isChecked = Prefs.autoEnter(this)
        checkAutoEnter.setOnCheckedChangeListener { _, checked -> Prefs.setAutoEnter(this, checked) }

        val presetLabels = Prefs.PRESETS.map { it.label }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presetLabels)

        val settings = Prefs.load(this)
        selectedId = settings.provider
        val index = Prefs.PRESETS.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        spinner.setSelection(index, false)
        editBaseUrl.setText(settings.baseUrl)
        editApiKey.setText(settings.apiKey)
        editModel.setText(settings.model)
        editContext.setText(settings.contextSize.toString())

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val preset = Prefs.PRESETS[position]
                if (preset.id == selectedId) return
                selectedId = preset.id
                editBaseUrl.setText(preset.baseUrl)
                editModel.setText(preset.model)
                editContext.setText(preset.contextSize.toString())
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
    }

    private fun save() {
        val settings = Prefs.ModelSettings(
            provider = selectedId,
            baseUrl = editBaseUrl.text.toString().trim(),
            apiKey = editApiKey.text.toString().trim(),
            model = editModel.text.toString().trim(),
            contextSize = editContext.text.toString().trim().toIntOrNull() ?: Prefs.preset(selectedId).contextSize,
        )
        Prefs.save(this, settings)

        if (!settings.isConfigured) {
            saveHint.setTextColor(0xFFB45309.toInt())
            saveHint.text = "已保存，但还缺少 API Key 或模型名称"
            return
        }

        val written = runCatching { ConfigWriter.write(this, settings) }
        if (written.isSuccess) {
            saveHint.setTextColor(0xFF059669.toInt())
            saveHint.text = "已保存并写入配置，重启运行环境后生效"
        } else {
            saveHint.setTextColor(0xFFDC2626.toInt())
            saveHint.text = "保存失败：${written.exceptionOrNull()?.message}"
        }
    }
}
