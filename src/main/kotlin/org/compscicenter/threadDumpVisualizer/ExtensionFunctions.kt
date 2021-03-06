package org.compscicenter.threadDumpVisualizer

import com.intellij.ide.dnd.DnDEvent
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.VirtualFile
import intellij.dumps.Dump
import intellij.dumps.ThreadInfo
import java.awt.Color
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.lang.Thread.State.*
import java.util.*

fun ThreadInfo.getStateColor(): Color = when (threadState) {
    BLOCKED, WAITING, TIMED_WAITING -> Color.RED
    RUNNABLE                        -> if (isYielding()) Color.ORANGE else Color.GREEN
    else                            -> Color.GREEN
}

fun ThreadInfo.weight() = when (getStateColor()) {
    Color.RED    -> 3
    Color.ORANGE -> 2
    Color.GREEN  -> 1
    else         -> 0
}

fun ThreadInfo.isPerformingRunReadAction() = when (stackTrace) {
    null -> false
    else -> stackTrace.asSequence().filter { it.methodName != null }.any { it.isPerformingRunReadAction() }
}

fun ThreadInfo.isYielding() = when (stackTrace) {
    null -> false
    else -> !stackTrace.isEmpty() && (stackTrace[0].methodName?.contains("yield") ?: false)
}

fun ThreadInfo.isRunning() = threadState == RUNNABLE && !isYielding()

fun String.isAWTThreadName() = startsWith("AWT-EventQueue")

fun ThreadInfo.isAWTThread() = threadName.isAWTThreadName()

fun Dump.findThreadByName(threadName: String?) = threads.find { it.threadName == threadName }

fun Dump.getAWTBlockingThreads(): List<ThreadInfo> {
    val awtThread = awtThread() ?: return emptyList()

    val blockingThreads = ArrayList<ThreadInfo>()
    val isAWTBlockedByRWLock = awtThread.lockName?.contains("ReadMostlyRWLock") ?: false

    if (isAWTBlockedByRWLock) {
        threads.asSequence()
                .filter { it !== awtThread }
                .filter { it.isPerformingRunReadAction() && (it.isRunning() || it.lockName != null) }
                .forEach { blockingThreads.add(it) }
    }

    awtThread.lockOwnerName?.let { blockingThreads.add(findThreadByName(it)!!) }

    return blockingThreads
}

fun Dump.getDependencyChain(thread: ThreadInfo): List<ThreadDumpDependency> {
    val res = ArrayList<ThreadDumpDependency>()
    var waiting = thread

    while (true) {
        val working = waiting.lockOwnerName?.let { findThreadByName(it) } ?: break

        res.add(ThreadDumpDependency(waiting, working))
        waiting = working
    }

    return res
}

fun Dump.getDependencyGraph(): List<ThreadDumpDependency> {
    val dependencyGraph = ArrayList<ThreadDumpDependency>().apply {
        val awtThread = awtThread() ?: return@apply
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

fun TextEditor.setViewerMode() {
    val editorEx = (editor as? EditorEx) ?: throw IllegalStateException("Editor is not instance of EditorEx")

    editorEx.isViewer = true
}

fun FileEditorManager.openReadOnly(file: VirtualFile, focusEditor: Boolean): TextEditor {
    val textEditor = openFile(file, focusEditor).single() as TextEditor

    return textEditor.apply { setViewerMode() }
}

fun FileContent.getThreadStateOffset(threadName: String) = highlightInfoList.asSequence()
        .filter { it.threadInfo.threadName == threadName }
        .find { it.highlightType == HighlightType.THREAD_STATE }?.startOffset

fun FileContent.getReadActionOffset(threadName: String) = highlightInfoList.asSequence()
        .filter { it.threadInfo.threadName == threadName }
        .find { it.highlightType == HighlightType.READ_ACTION }?.startOffset

fun Color.stringName() = when (this) {
    Color.RED    -> "red"
    Color.GREEN  -> "green"
    Color.ORANGE -> "orange"
    else         -> "undefined"
}

fun String.initials(): String {
    return split(Regex("\\s+")).asSequence()
            .filter(String::isNotEmpty)
            .map { if (it[0].isLetter()) "${it[0].toUpperCase()}" else it }
            .joinToString(separator = "")
}