package org.compscicenter.typingFreezeAnalyzer

import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import org.bson.types.ObjectId
import org.mongodb.morphia.annotations.Entity
import org.mongodb.morphia.annotations.Id
import org.mongodb.morphia.annotations.Transient
import java.util.*

enum class HighlightType {
    LINK,
    THREAD_STATE,
    READ_ACTION
}

class ClassLinkInfo(val className: String,
                    val lineNumber: Int,
                    val highlightInfo: HighlightInfo)

class HighlightInfo(val threadInfo: ThreadInfoDigest,
                    val startOffset: Int,
                    val endOffset: Int,
                    val textAttributes: TextAttributes,
                    val highlightType: HighlightType,
                    val targetArea: HighlighterTargetArea = HighlighterTargetArea.EXACT_RANGE)

class FileContent(val text: String,
                  val classLinkInfoList: List<ClassLinkInfo>,
                  val highlightInfoList: List<HighlightInfo>)


class MongoConfig(map: Map<String, Any>) {
    constructor(host: String = "127.0.0.1", port: Int = 27017, dbName: String)
            : this(mapOf("host" to host, "port" to port, "dbName" to dbName))

    val host: String by map
    val port: Int by map
    val dbName: String by map
}

data class ThreadInfoDigest(val threadName: String,
                            val threadId: Long,
                            val lockName: String?,
                            val lockOwnerName: String?,
                            val inNative: Boolean,
                            val suspended: Boolean,
                            val threadState: Thread.State,
                            val stackTrace: List<StackTraceElement>?) {
    private constructor(builder: Builder) :
            this(builder.threadName,
                 builder.threadId,
                 builder.lockName,
                 builder.lockOwnerName,
                 builder.inNative,
                 builder.suspended,
                 builder.threadState,
                 builder.stackTrace)

    class Builder {
        lateinit var threadName: String
            private set
        var threadId: Long = 0L
            private set
        var lockName: String? = null
            private set
        var lockOwnerName: String? = null
            private set
        var inNative: Boolean = false
            private set
        var suspended: Boolean = false
            private set
        lateinit var threadState: Thread.State
            private set
        var stackTrace = ArrayList<StackTraceElement>()
            private set

        fun threadName(threadName: String) = apply { this.threadName = threadName }
        fun lockName(lockName: String?) = apply { this.lockName = lockName }
        fun lockOwnerName(lockOwnerName: String?) = apply { this.lockOwnerName = lockOwnerName }
        fun inNative(inNative: Boolean) = apply { this.inNative = inNative }
        fun suspended(suspended: Boolean) = apply { this.suspended = suspended }
        fun threadState(threadState: Thread.State) = apply { this.threadState = threadState }
        fun stackTrace(stackTraceElement: StackTraceElement) = apply { stackTrace.add(stackTraceElement) }

        fun build() = ThreadInfoDigest(this)
    }
}

@Entity("ThreadDumps")
class ThreadDumpInfo() {
    @delegate:Transient val awtThread: ThreadInfoDigest by lazy {
        threadInfos.find { it.isAWTThread() } ?: throw IllegalStateException("AWT thread is missed")
    }
    @Id lateinit var objectId: ObjectId
    lateinit var version: String
    lateinit var product: String
    lateinit var buildNumber: String
    lateinit var threadInfos: List<ThreadInfoDigest>

    override fun toString() = objectId.toString()
}

fun main(args: Array<String>) {
    ThreadDumpDaoMongo(MongoConfig(dbName = "test")).getAllThreadDumps().forEach {
        println(createFileContent(it).text)
    }
}