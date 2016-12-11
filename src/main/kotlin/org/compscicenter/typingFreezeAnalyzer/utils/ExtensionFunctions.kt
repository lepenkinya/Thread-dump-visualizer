package org.compscicenter.typingFreezeAnalyzer.utils

import com.intellij.ide.dnd.DnDEvent
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.MarkupModel
import org.compscicenter.typingFreezeAnalyzer.*
import java.awt.Color
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.util.*

fun ThreadInfoDigest.getStateColor(): Color = when (threadState) {
    Thread.State.BLOCKED, Thread.State.WAITING, Thread.State.TIMED_WAITING -> Color.RED
    Thread.State.RUNNABLE -> if (isYielding()) Color.ORANGE else Color.GREEN
    else -> Color.GREEN
}

fun ThreadInfoDigest.isSignificant(vararg packagesToSkip: String = arrayOf("java", "sun", "com.sun")) = when (stackTrace) {
    null -> false
    else -> stackTrace.asSequence()
            .filter { it.className != null }
            .any { stackTrace -> packagesToSkip.none { stackTrace.className.startsWith(it) } }
}

fun ThreadInfoDigest.weight() = when (getStateColor()) {
    Color.RED -> 3
    Color.ORANGE -> 2
    Color.GREEN -> 1
    else -> 0
}

fun ThreadInfoDigest.isPerformingRunReadAction() = when (stackTrace) {
    null -> false
    else -> stackTrace.asSequence().filter { it.methodName != null }.any { it.isPerformingRunReadAction() }
}

fun ThreadInfoDigest.isYielding() = when {
    stackTrace == null || stackTrace.isEmpty() || stackTrace[0].methodName == null -> false
    else -> "yield" in stackTrace[0].methodName
}

fun ThreadInfoDigest.isRunning() = threadState == Thread.State.RUNNABLE && !isYielding()

fun ThreadInfoDigest.isAWTThread() = threadName.startsWith("AWT-EventQueue")

fun ThreadDumpInfo.findThreadByName(threadName: String?) = threadList.find { it.threadName == threadName }

fun ThreadDumpInfo.getBlockingThreads() = threadList.filter {
    it.isPerformingRunReadAction() && (it.isRunning() || it.lockOwnerName != null)
}

fun ThreadDumpInfo.getDependencyGraph(): List<Pair<ThreadInfoDigest, ThreadInfoDigest>> {
    val blockingThreads = getBlockingThreads()

    val dependencyGraph = ArrayList<Pair<ThreadInfoDigest, ThreadInfoDigest>>().apply {
        with(awtThread) { if (lockOwnerName != null) add(this to findThreadByName(lockOwnerName)!!) }
        addAll(blockingThreads.map { awtThread to it })
        addAll(blockingThreads.filter { it.lockOwnerName != null }.map { it to findThreadByName(it.lockOwnerName)!! })
    }

    return dependencyGraph
}

fun StackTraceElement.isResolvable() = className != null && fileName != null && !isNativeMethod && lineNumber >= 0
fun StackTraceElement.isPerformingRunReadAction() = methodName.contains("runReadAction", ignoreCase = true)

fun Transferable.getFiles(event: DnDEvent): List<File>? {
    val dataFlavor = event.transferDataFlavors.find { it == DataFlavor.javaFileListFlavor } ?: return null
    val files = getTransferData(dataFlavor) as? List<*> ?: return null

    return files.map { it as File }
}

fun MarkupModel.addRangeHighlighter(highlightInfo: HighlightInfo) {
    with(highlightInfo) {
        addRangeHighlighter(startOffset, endOffset, HighlighterLayer.SYNTAX, textAttributes, targetArea)
    }
}

fun FileContent.getThreadStateOffset(threadName: String) = highlightInfoList.asSequence()
        .filter { it.threadInfo.threadName == threadName }
        .find { it.highlightType == HighlightType.THREAD_STATE }?.startOffset

fun FileContent.getReadActionOffset(threadName: String) = highlightInfoList.asSequence()
        .filter { it.threadInfo.threadName == threadName }
        .find { it.highlightType == HighlightType.READ_ACTION }?.startOffset

fun Color.stringName() = when (this) {
    Color.RED -> "red"
    Color.GREEN -> "green"
    Color.ORANGE -> "orange"
    else -> "undefined"
}

fun String.initials(): String {
    return split(Regex("\\s+")).asSequence()
            .filter(String::isNotEmpty)
            .map { if (it[0].isLetter()) "${it[0].toUpperCase()}" else it }
            .joinToString(separator = "")
}