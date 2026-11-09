package ai.kimi.code.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in check against the real rootfs archive: `-Dkimi.rootfs=/path/to.tar.gz`. */
class RealRootfsExtractTest {

    @Test
    fun extractsTheShippedRootfs() {
        val path = System.getProperty("kimi.rootfs") ?: ""
        assumeTrue("kimi.rootfs not provided", path.isNotEmpty())
        val archive = File(path)
        assumeTrue("archive missing", archive.isFile)

        val dest = File(System.getProperty("java.io.tmpdir"), "rootfs-test-${System.nanoTime()}")
        dest.mkdirs()
        val start = System.currentTimeMillis()
        var files = 0
        TarExtractor.extractGzip(archive, dest) { files = it.files }
        val elapsed = System.currentTimeMillis() - start
        println("extracted $files files in ${elapsed}ms")

        assertTrue(File(dest, "opt/node/bin/node").isFile)
        assertTrue(File(dest, "opt/node/bin/node").canExecute())
        assertTrue(File(dest, "opt/kimi/dist/main.mjs").isFile)
        assertTrue(File(dest, "opt/kimi/entrypoint.sh").canExecute())
        assertTrue(File(dest, "root/.kimi-code/bin/rg").canExecute())
        assertTrue(File(dest, "usr/local/bin/rg").isFile)
        assertEquals("usr/bin", java.nio.file.Files.readSymbolicLink(File(dest, "bin").toPath()).toString())
        assertEquals("usr/lib", java.nio.file.Files.readSymbolicLink(File(dest, "lib").toPath()).toString())
        assertTrue(File(dest, "usr/bin/bash").canExecute())
        dest.deleteRecursively()
    }
}
