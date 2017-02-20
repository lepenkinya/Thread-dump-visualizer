import org.apache.commons.lang3.EnumUtils
import org.compscicenter.threadDumpVisualizer.HighlightType
import org.compscicenter.threadDumpVisualizer.createFileContent
import org.compscicenter.threadDumpVisualizer.parseThreadDump
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HighlightTest {
    val classLoader: ClassLoader = javaClass.classLoader

    @Test
    fun `Thread states must be highlighted`() {
        val inputStream = classLoader.getResourceAsStream("good-dump.txt")
        val content = inputStream.parseThreadDump().createFileContent()
        val threadStateHighlight = content.highlightInfoList.filter { it.highlightType == HighlightType.THREAD_STATE }

        assertEquals(threadStateHighlight.size, 2)

        threadStateHighlight.forEach {
            val s = content.text.substring(it.startOffset, it.endOffset)

            assertTrue(EnumUtils.isValidEnum(Thread.State::class.java, s))
        }
    }
}