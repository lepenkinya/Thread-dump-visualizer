import org.compscicenter.threadDumpVisualizer.findThreadByName
import org.compscicenter.threadDumpVisualizer.parseThreadDump
import org.junit.Test
import kotlin.test.assertEquals

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
        val threadList = dump.threadList

        assertEquals(dump.version, "2016.3")
        assertEquals(dump.buildNumber, "IU-163.SNAPSHOT")
        assertEquals(dump.product, "IDEA")

        assertEquals(threadList.size, 2)

        val awtThread = dump.awtThread

        assertEquals(awtThread.threadState, Thread.State.WAITING)
        assertEquals(awtThread.lockName, "sun.java2d.opengl.OGLRenderQueue\$QueueFlusher")
        assertEquals(awtThread.lockOwnerName, "Java2D Queue Flusher")

        val awtStackTrace = awtThread.stackTrace
        assertEquals(awtStackTrace!!.size, 5)
        assertEquals(awtStackTrace[0].isNativeMethod, true)
        assertEquals(awtStackTrace[0].methodName, "\$\$YJP\$\$wait")
        assertEquals(awtStackTrace[1].isNativeMethod, false)
        assertEquals(awtStackTrace[1].lineNumber, -1)
        assertEquals(awtStackTrace[2].isNativeMethod, false)
        assertEquals(awtStackTrace[2].lineNumber, 502)

        val java2DThread = dump.findThreadByName("Java2D Queue Flusher")!!

        assertEquals(java2DThread.threadState, Thread.State.RUNNABLE)
        assertEquals(java2DThread.inNative, true)
        assertEquals(java2DThread.stackTrace?.size, 5)
        assertEquals(java2DThread.lockName, null)
        assertEquals(java2DThread.lockOwnerName, null)
    }
}