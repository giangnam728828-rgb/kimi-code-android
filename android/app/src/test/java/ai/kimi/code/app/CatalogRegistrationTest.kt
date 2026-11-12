package ai.kimi.code.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "register the whole catalogue under one provider" contract that
 * makes every model of a single API endpoint show up in the web UI's dropdown.
 * Before this, config.toml only ever contained one `[models.*]` section per
 * provider, so switching to any other model meant going back to settings.
 */
class CatalogRegistrationTest {

    @Test
    fun `registers the entire catalogue minus the default model`() {
        val extras = ModelCatalog.registrationExtras(
            current = "deepseek-v4.1-flash",
            extra = emptyList(),
            catalog = listOf("deepseek-v4.1-flash", "deepseek-r1", "deepseek-v3"),
            cap = 40,
        )
        assertEquals(listOf("deepseek-r1", "deepseek-v3"), extras)
    }

    @Test
    fun `default model is excluded even when catalogue uses different case`() {
        val extras = ModelCatalog.registrationExtras(
            current = "DeepSeek-V4.1-Flash",
            extra = emptyList(),
            catalog = listOf("deepseek-v4.1-flash", "DEEPSEEK-R1"),
            cap = 40,
        )
        // Case-insensitive dedup must drop the default's alias in the catalogue.
        assertEquals(listOf("DEEPSEEK-R1"), extras)
    }

    @Test
    fun `catalogue wins over manual extras but manual ids are preserved`() {
        val extras = ModelCatalog.registrationExtras(
            current = "a",
            extra = listOf("manual-1", "b"),
            catalog = listOf("b", "c"),
            cap = 40,
        )
        assertEquals(listOf("b", "c", "manual-1"), extras)
    }

    @Test
    fun `duplicate ids differing only by case collapse into one section`() {
        val extras = ModelCatalog.registrationExtras(
            current = "x",
            extra = listOf("DUP", "dup"),
            catalog = listOf("Dup", "DUP", "dup"),
            cap = 40,
        )
        assertEquals(listOf("Dup"), extras)
    }

    @Test
    fun `blank ids and whitespace are dropped`() {
        val extras = ModelCatalog.registrationExtras(
            current = "  keep  ",
            extra = listOf("", "   ", "  spaced  "),
            catalog = listOf("", "ok"),
            cap = 40,
        )
        assertEquals(listOf("ok", "spaced"), extras)
    }

    @Test
    fun `cap bounds the menu length and keeps the first gateway ids`() {
        val catalog = (1..200).map { "m-$it" }
        val extras = ModelCatalog.registrationExtras("m-1", emptyList(), catalog, 40)
        // cap bounds the extras only; the default m-1 is skipped, not counted.
        assertEquals(40, extras.size)
        assertEquals("m-2", extras.first())
        assertEquals("m-41", extras.last())
        assertTrue(!extras.contains("m-1"))
    }

    @Test
    fun `zero or negative cap produces no registrations`() {
        assertTrue(ModelCatalog.registrationExtras("a", listOf("b"), listOf("c"), 0).isEmpty())
        assertTrue(ModelCatalog.registrationExtras("a", listOf("b"), listOf("c"), -5).isEmpty())
    }

    @Test
    fun `empty catalogue keeps previously registered manual models`() {
        val extras = ModelCatalog.registrationExtras(
            current = "a",
            extra = listOf("manual-1", "manual-2"),
            catalog = emptyList(),
            cap = 40,
        )
        assertEquals(listOf("manual-1", "manual-2"), extras)
    }

    @Test
    fun `result never contains the default under any casing`() {
        val extras = ModelCatalog.registrationExtras(
            current = "Spark-X2.6",
            extra = listOf("SPARK-X2.6", "spark-x2.6"),
            catalog = listOf("Spark-X2.6", "other"),
            cap = 40,
        )
        assertEquals(listOf("other"), extras)
    }
}
