package org.compscicenter.typingFreezeAnalyzer

import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import org.bson.types.ObjectId
import org.mongodb.morphia.annotations.Entity
import org.mongodb.morphia.annotations.Id
import java.awt.Color
import java.awt.Font
import java.lang.management.ThreadInfo

class LinkInfo(val highlightStartOffset: Int,
               val highlightEndOffset: Int,
               val className: String,
               val lineNumber: Int) {
    object HighlightInfo {
        val color = TextAttributes(Color.BLUE, Color.WHITE, Color.BLACK, EffectType.LINE_UNDERSCORE, Font.PLAIN)
    }
}

class FileContent(val text: String, val linkInfoList: List<LinkInfo>)

@Entity(DatabaseInfo.TABLE_NAME)
class ThreadDumpInfo() {
    @Id lateinit var objectId: ObjectId
    val isAWTThreadBlocked: Boolean by lazy {
        val blockedStates = listOf(Thread.State.TIMED_WAITING, Thread.State.WAITING, Thread.State.BLOCKED)
        threadInfos.any { it.isAWTThread() && (it.threadState in blockedStates) }
    }
    lateinit var version: String
    lateinit var product: String
    lateinit var buildNumber: String
    lateinit var threadInfos: List<ThreadInfo>
}