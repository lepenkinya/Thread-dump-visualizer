package org.compscicenter.threadDumpVisualizer

import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import org.compscicenter.threadDumpVisualizer.createFileContent
import org.compscicenter.threadDumpVisualizer.isAWTThread
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

class ThreadInfoDigest(val threadName: String,
                       val lockName: String?,
                       val lockOwnerName: String?,
                       val inNative: Boolean,
                       val suspended: Boolean,
                       val threadState: Thread.State,
                       val stackTrace: List<StackTraceElement>?) {
    private constructor(builder: Builder) :
            this(builder.threadName,
                 builder.lockName,
                 builder.lockOwnerName,
                 builder.inNative,
                 builder.suspended,
                 builder.threadState,
                 builder.stackTrace)

    class Builder {
        lateinit var threadName: String
            private set
        lateinit var threadState: Thread.State
            private set
        var lockName: String? = null
            private set
        var lockOwnerName: String? = null
            private set
        var inNative: Boolean = false
            private set
        var suspended: Boolean = false
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

class ThreadDumpInfo(val name: String,
                     val version: String?,
                     val product: String?,
                     val buildNumber: String?,
                     val threadList: List<ThreadInfoDigest>) {
    private constructor(builder: Builder) : this(builder.name,
                                                 builder.version,
                                                 builder.product,
                                                 builder.buildNumber,
                                                 builder.threadInfos)

    @Entity("ThreadDumps")
    class Builder {
        @Id
        lateinit var name: String
            private set
        var version: String? = null
            private set
        var product: String? = null
            private set
        var buildNumber: String? = null
            private set
        var threadInfos = ArrayList<ThreadInfoDigest>()
            private set

        fun name(name: String) = apply { this.name = name }
        fun version(version: String) = apply { this.version = version }
        fun product(product: String) = apply { this.product = product }
        fun buildNumber(buildNumber: String) = apply { this.buildNumber = buildNumber }
        fun threadInfo(infoDigest: ThreadInfoDigest) = apply { threadInfos.add(infoDigest) }

        fun build(): ThreadDumpInfo {
            if (threadInfos.isEmpty()) throw IllegalStateException("Thread info list must not be empty")

            return ThreadDumpInfo(this)
        }
    }

    @delegate:Transient
    val awtThread: ThreadInfoDigest by lazy {
        threadList.find { it.isAWTThread() } ?: throw IllegalStateException("AWT thread is missed")
    }

    override fun toString() = name
}