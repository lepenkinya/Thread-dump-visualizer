package org.compscicenter.typingFreezeAnalyzer

import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import org.bson.types.ObjectId
import org.mongodb.morphia.annotations.Entity
import org.mongodb.morphia.annotations.Id
import org.mongodb.morphia.annotations.Transient
import java.lang.management.ThreadInfo

class ClassLinkInfo(val className: String,
                    val lineNumber: Int,
                    val highlightInfo: HighlightInfo)

class HighlightInfo(val startOffset: Int,
                    val endOffset: Int,
                    val textAttributes: TextAttributes,
                    val targetArea: HighlighterTargetArea = HighlighterTargetArea.EXACT_RANGE)

class FileContent(val text: String,
                  val classLinkInfoList: List<ClassLinkInfo>,
                  val highlightInfoList: List<HighlightInfo>)

@Entity(DatabaseInfo.TABLE_NAME)
class ThreadDumpInfo() {
    @delegate:Transient val isAWTThreadBlocked: Boolean by lazy {
        val blockedStates = listOf(Thread.State.TIMED_WAITING, Thread.State.WAITING, Thread.State.BLOCKED)
        threadInfos.any { it.isAWTThread() && (it.threadState in blockedStates) }
    }
    @Id lateinit var objectId: ObjectId
    lateinit var version: String
    lateinit var product: String
    lateinit var buildNumber: String
    lateinit var threadInfos: List<ThreadInfo>
}