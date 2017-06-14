package intellij.dumps

import intellij.dumps.Utils.content
import org.compscicenter.threadDumpVisualizer.dumpChunks
import org.junit.Test


class ChunksParsingTest {

    @Test
    fun `test ij dump`() {
        val text = content("samples/ij_dump.txt").reader().readText()
        val chunks = text.dumpChunks()
        val (isOK, message) = chunks.validate()
        assert(isOK, { message })
    }

    @Test
    fun `test jstack dump`() {
        val text = content("samples/jstack_dump.txt").reader().readText()
        val chunks = text.dumpChunks()
        val (isOK, message) = chunks.validate()
        assert(isOK, { message })
    }

}