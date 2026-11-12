package ai.kimi.code.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefsCodecTest {

    private val fallback = 131072

    @Test
    fun `round trip keeps the effort field`() {
        val records = listOf(
            PrefsCodec.Record("kimi", "kimi", "sk-1", "https://api.moonshot.cn/v1", "k2", 262144, "high"),
            PrefsCodec.Record("custom", "my", "sk-2", "", "gpt", 131072, ""),
        )
        assertEquals(records, PrefsCodec.decode(PrefsCodec.encode(records), fallback))
    }

    @Test
    fun `six field legacy records still decode`() {
        val legacy = listOf(
            "kimi", "kimi", "sk-old", "https://api.moonshot.cn/v1", "k2", "262144",
        ).joinToString(PrefsCodec.FIELD_SEP)
        val decoded = PrefsCodec.decode(legacy, fallback)
        assertEquals(1, decoded.size)
        assertEquals("sk-old", decoded[0].apiKey)
        assertEquals(262144, decoded[0].contextSize)
        assertEquals("", decoded[0].effort)
    }

    @Test
    fun `malformed records are skipped without breaking the rest`() {
        val raw = listOf(
            "broken",
            listOf("kimi", "kimi", "sk-ok", "url", "k2", "262144", "low")
                .joinToString(PrefsCodec.FIELD_SEP),
        ).joinToString(PrefsCodec.RECORD_SEP)
        val decoded = PrefsCodec.decode(raw, fallback)
        assertEquals(1, decoded.size)
        assertEquals("low", decoded[0].effort)
    }

    @Test
    fun `bad context size falls back instead of throwing`() {
        val raw = listOf("kimi", "kimi", "sk", "url", "k2", "not-a-number", "medium")
            .joinToString(PrefsCodec.FIELD_SEP)
        assertEquals(fallback, PrefsCodec.decode(raw, fallback)[0].contextSize)
    }

    @Test
    fun `empty input decodes to an empty list`() {
        assertEquals(emptyList<PrefsCodec.Record>(), PrefsCodec.decode("", fallback))
    }
}
