package ai.kimi.code.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

class TarExtractorTest {

    private fun writeTar(entries: List<Triple<String, Char, ByteArray>>, out: File) {
        GZIPOutputStream(FileOutputStream(out)).use { gz ->
            for ((name, type, data) in entries) {
                gz.write(header(name, type, data.size.toLong()))
                gz.write(data)
                val pad = (512 - data.size % 512) % 512
                if (pad > 0) gz.write(ByteArray(pad))
            }
            gz.write(ByteArray(1024))
        }
    }

    private fun header(name: String, type: Char, size: Long): ByteArray {
        val h = ByteArray(512)
        val n = name.toByteArray()
        System.arraycopy(n, 0, h, 0, minOf(n.size, 100))
        putOctal(h, 100, 8, 0b111_101_101L)
        putOctal(h, 108, 8, 0)
        putOctal(h, 116, 8, 0)
        putOctal(h, 124, 12, size)
        putOctal(h, 136, 12, 0)
        h[156] = type.code.toByte()
        val magic = "ustar".toByteArray()
        System.arraycopy(magic, 0, h, 257, magic.size)
        var sum = 0
        for (i in 0 until 512) {
            val v = h[i].toInt() and 0xFF
            sum += if (i in 148 until 156) ' '.code else v
        }
        val chk = String.format("%06o", sum).toByteArray()
        System.arraycopy(chk, 0, h, 148, chk.size)
        h[154] = 0
        h[155] = ' '.code.toByte()
        return h
    }

    private fun putOctal(h: ByteArray, off: Int, len: Int, value: Long) {
        val s = java.lang.Long.toOctalString(value).padStart(len - 1, '0') + "\u0000"
        System.arraycopy(s.toByteArray(), 0, h, off, len)
    }

    @Test
    fun extractsNestedFilesAndPreservesExecBit() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "tartest-${System.nanoTime()}")
        val archive = File(tmp, "t.tar.gz")
        val dest = File(tmp, "root")
        tmp.mkdirs()
        writeTar(
            listOf(
                Triple("./bin", '5', ByteArray(0)),
                Triple("./bin/hello.sh", '0', "#!/bin/sh\necho hi\n".toByteArray()),
                Triple("./opt/node/bin/node", '0', ByteArray(2048) { 'x'.code.toByte() }),
            ),
            archive,
        )
        TarExtractor.extractGzip(archive, dest) {}
        assertEquals("#!/bin/sh\necho hi\n", File(dest, "bin/hello.sh").readText())
        assertTrue(File(dest, "bin/hello.sh").canExecute())
        assertEquals(2048L, File(dest, "opt/node/bin/node").length())
        tmp.deleteRecursively()
    }

    @Test
    fun refusesPathTraversal() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "tartest2-${System.nanoTime()}")
        val archive = File(tmp, "t.tar.gz")
        val dest = File(tmp, "root")
        tmp.mkdirs()
        writeTar(listOf(Triple("../../evil.txt", '0', "bad".toByteArray())), archive)
        TarExtractor.extractGzip(archive, dest) {}
        assertTrue(!File(dest, "../../evil.txt").exists())
        assertTrue(!File(tmp.parentFile, "evil.txt").exists())
        tmp.deleteRecursively()
    }
}
