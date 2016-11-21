package org.compscicenter.typingFreezeAnalyzer

import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import org.bson.types.ObjectId
import org.mongodb.morphia.annotations.Entity
import org.mongodb.morphia.annotations.Id
import org.mongodb.morphia.annotations.Transient
import java.lang.management.ThreadInfo

enum class HighlightType {
    LINK,
    THREAD_STATE,
    READ_ACTION
}

class ClassLinkInfo(val className: String,
                    val lineNumber: Int,
                    val highlightInfo: HighlightInfo)

class HighlightInfo(val threadInfo: ThreadInfo,
                    val startOffset: Int,
                    val endOffset: Int,
                    val textAttributes: TextAttributes,
                    val highlightType: HighlightType,
                    val targetArea: HighlighterTargetArea = HighlighterTargetArea.EXACT_RANGE)

class FileContent(val text: String,
                  val classLinkInfoList: List<ClassLinkInfo>,
                  val highlightInfoList: List<HighlightInfo>)

@Entity(DatabaseInfo.TABLE_NAME)
class ThreadDumpInfo() {
    @delegate:Transient val awtThread : ThreadInfo by lazy {
        threadInfos.find { it.isAWTThread() } ?: throw IllegalStateException("AWT thread is missed")
    }
    @delegate:Transient val isAWTThreadWaiting: Boolean by lazy {
        val waitingStates = setOf(Thread.State.TIMED_WAITING, Thread.State.WAITING, Thread.State.BLOCKED)
        (awtThread.threadState in waitingStates) || awtThread.isYielding()
    }
    @Id lateinit var objectId: ObjectId
    lateinit var version: String
    lateinit var product: String
    lateinit var buildNumber: String
    lateinit var threadInfos: List<ThreadInfo>
}