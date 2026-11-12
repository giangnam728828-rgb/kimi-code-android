package ai.kimi.code.app

/**
 * Pure (Android-free) codec for the model list kept in SharedPreferences.
 *
 * Field 7 (`effort`) was added for thinking-effort support and field 8
 * (`models`) for multi-model providers. Records written by older versions have
 * only six or seven fields and must still decode with empty defaults, otherwise
 * upgrading the app would silently drop every configured model -- including the
 * API keys.
 */
object PrefsCodec {

    const val RECORD_SEP = "\u001E"
    const val FIELD_SEP = "\u001F"
    private const val MODEL_SEP = "\u001D"
    private const val MIN_FIELDS = 6

    data class Record(
        val presetId: String,
        val alias: String,
        val apiKey: String,
        val baseUrl: String,
        val model: String,
        val contextSize: Int,
        /** Empty means "follow whatever the server / workspace configured". */
        val effort: String,
        /** Extra model ids registered under the same provider/API key. */
        val models: String = "",
    )

    fun encode(records: List<Record>): String = records.joinToString(RECORD_SEP) { r ->
        listOf(
            r.presetId,
            r.alias,
            r.apiKey,
            r.baseUrl,
            r.model,
            r.contextSize.toString(),
            r.effort,
            r.models,
        ).joinToString(FIELD_SEP)
    }

    fun decode(raw: String, fallbackContext: Int): List<Record> = raw
        .split(RECORD_SEP)
        .filter { it.isNotBlank() }
        .mapNotNull { record ->
            val f = record.split(FIELD_SEP)
            if (f.size < MIN_FIELDS) return@mapNotNull null
            Record(
                presetId = f[0],
                alias = f[1],
                apiKey = f[2],
                baseUrl = f[3],
                model = f[4],
                contextSize = f[5].toIntOrNull() ?: fallbackContext,
                effort = f.getOrNull(6) ?: "",
                models = f.getOrNull(7) ?: "",
            )
        }

    /** Model ids can contain commas but never a control separator. */
    fun joinModels(ids: List<String>): String = ids
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(MODEL_SEP)

    fun splitModels(raw: String): List<String> = raw
        .split(MODEL_SEP)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}
