package org.compscicenter.threadDumpVisualizer

import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import intellij.dumps.Dump
import intellij.dumps.ThreadInfo

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

class MongoConfig(map: Map<String, Any>) {
    constructor(host: String = "127.0.0.1", port: Int = 27017, dbName: String)
            : this(mapOf("host" to host, "port" to port, "dbName" to dbName))

    val host: String by map
    val port: Int by map
    val dbName: String by map
}



fun Dump.awtThread(): ThreadInfo? = threads.find { it.isAWTThread() }