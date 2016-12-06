import org.compscicenter.typingFreezeAnalyzer.utils.findThreadByName
import org.compscicenter.typingFreezeAnalyzer.utils.getDependencyGraph
import org.compscicenter.typingFreezeAnalyzer.utils.parseThreadDump
import org.junit.Test
import kotlin.test.assertEquals

class ThreadDependencyTest {
    val classLoader: ClassLoader = javaClass.classLoader

    @Test
    fun `One dependency`() {
        val inputStream = classLoader.getResourceAsStream("good-dump.txt")
        val dump = inputStream.parseThreadDump()

        val dependencies = dump.getDependencyGraph()

        assertEquals(dependencies.size, 1)

        val (waiting, working) = dependencies[0]

        assertEquals(waiting, dump.awtThread)
        assertEquals(working, dump.findThreadByName("Java2D Queue Flusher"))
    }
}