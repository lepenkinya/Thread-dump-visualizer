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

fun String.isAWTThreadName() = startsWith("AWT-EventQueue")

fun ThreadInfoDigest.isAWTThread() = threadName.isAWTThreadName()

fun ThreadDumpInfo.findThreadByName(threadName: String?) = threadList.find { it.threadName == threadName }

fun ThreadDumpInfo.getAWTBlockingThreads(): List<ThreadInfoDigest> {
    val blockingThreads = ArrayList<ThreadInfoDigest>()

    threadList.asSequence()
            .filter { it !== awtThread }
            .filter { it.isPerformingRunReadAction() && (it.isRunning() || it.lockOwnerName != null) }
            .forEach { blockingThreads.add(it) }

    awtThread.lockOwnerName?.let { blockingThreads.add(findThreadByName(it)!!) }

    return blockingThreads
}

fun ThreadDumpInfo.getDependencyChain(thread: ThreadInfoDigest): List<ThreadDumpDependency> {
    val res = ArrayList<ThreadDumpDependency>()
    var waiting = thread

    while (true) {
        val working = waiting.lockOwnerName?.let { findThreadByName(it) } ?: break

        res.add(ThreadDumpDependency(ThreadPresentation(waiting), ThreadPresentation(working)))
        waiting = working
    }

    return res
}

fun ThreadDumpInfo.getDependencyGraph(): List<ThreadDumpDependency> {
    val dependencyGraph = ArrayList<ThreadDumpDependency>().apply {
        val awtBlockingThreads = getAWTBlockingThreads()

        addAll(awtBlockingThreads.map {
            val waiting = ThreadPresentation(awtThread)
            val working = ThreadPresentation(it)

            ThreadDumpDependency(waiting, working)
        })

        addAll(awtBlockingThreads.flatMap { getDependencyChain(it) })
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