import org.compscicenter.typingFreezeAnalyzer.utils.findThreadByName
import org.compscicenter.typingFreezeAnalyzer.utils.parseThreadDump
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FileParserTest {
    val classLoader: ClassLoader = javaClass.classLoader

    @Test(expected = IllegalStateException::class)
    fun `Parse bad file`() {
        val inputStream = classLoader.getResourceAsStream("wrong-file.txt")

        inputStream.parseThreadDump()
    }

    @Test
    fun `Parse good file`() {
        val inputStream = classLoader.getResourceAsStream("good-dump.txt")
        val dump = inputStream.parseThreadDump()
        val threadInfos = dump.threadList

        assertEquals(threadInfos.size, 2)

        val awtThread = dump.awtThread

        assertEquals(awtThread.stackTrace?.size, 5)
        assertEquals(awtThread.threadState, Thread.State.WAITING)
        assertNotNull(awtThread.lockName)
        assertEquals(awtThread.lockOwnerName, "Java2D Queue Flusher")

        val java2DThread = dump.findThreadByName("Java2D Queue Flusher")!!

        assertEquals(java2DThread.threadState, Thread.State.RUNNABLE)
        assertEquals(java2DThread.inNative, true)
        assertEquals(java2DThread.stackTrace?.size, 5)
    }
}