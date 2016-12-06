import org.apache.commons.lang3.EnumUtils
import org.compscicenter.typingFreezeAnalyzer.HighlightType
import org.compscicenter.typingFreezeAnalyzer.utils.createFileContent
import org.compscicenter.typingFreezeAnalyzer.utils.parseThreadDump
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HighlightTest {
    val classLoader: ClassLoader = javaClass.classLoader

    @Test
    fun `Thread states must be highlighted`() {
        val inputStream = classLoader.getResourceAsStream("good-dump.txt")
        val dump = inputStream.parseThreadDump()

        val content = createFileContent(dump)
        val threadStateHighlight = content.highlightInfoList.filter { it.highlightType == HighlightType.THREAD_STATE }

        assertEquals(threadStateHighlight.size, 2)

        threadStateHighlight.forEach {
            val s = content.text.substring(it.startOffset, it.endOffset)

            assertTrue(EnumUtils.isValidEnum(Thread.State::class.java, s))
        }
    }
}