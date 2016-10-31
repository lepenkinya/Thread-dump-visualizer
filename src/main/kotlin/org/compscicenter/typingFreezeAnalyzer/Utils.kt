package org.compscicenter.typingFreezeAnalyzer

import com.intellij.codeEditor.JavaEditorFileSwapper
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.impl.EditorHyperlinkSupport
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import java.lang.management.ThreadInfo

fun getReadableState(state: Thread.State) = when (state) {
    Thread.State.BLOCKED -> "blocked"
    Thread.State.TIMED_WAITING, Thread.State.WAITING -> "waiting on condition"
    Thread.State.RUNNABLE -> "runnable"
    Thread.State.NEW -> "new"
    Thread.State.TERMINATED -> "terminated"
}

fun printStackTrace(builder: StringBuilder,
                    stackTraceElements: Array<StackTraceElement>,
                    linkInfoList: MutableList<LinkInfo>) {
    val lineSeparator = System.lineSeparator()

    stackTraceElements.forEach {
        builder.append("    at ")

        if (it.className != null && it.fileName != null && !it.isNativeMethod && it.lineNumber >= 0) {
            val highlightStartOffset = builder.length + it.className.length + 1 + it.methodName.length + 1
            val highlightEndOffset = highlightStartOffset + it.fileName.length + 1 + it.lineNumber.toString().length
            val info = LinkInfo(highlightStartOffset, highlightEndOffset, it.className.substringBefore("$"), it.lineNumber)

            linkInfoList.add(info)
        }

        builder.append("$it$lineSeparator")
    }

    builder.append(lineSeparator)
}

fun dumpThreadInfo(info: ThreadInfo,
                   builder: StringBuilder,
                   linkInfoList: MutableList<LinkInfo>) {
    builder.appendln("\"${info.threadName}\" tid=${info.threadId} ${getReadableState(info.threadState)}")
    builder.append("    java.lang.Thread.State: ${info.threadState}")

    info.lockName?.let { builder.append(" on ${info.lockName}") }
    info.lockOwnerName?.let { builder.append(" owned by \"${info.lockOwnerName}\" Id=${info.lockOwnerId}") }

    builder.appendln()

    if (info.isSuspended) {
        builder.append(" (suspended)")
    }

    if (info.isInNative) {
        builder.append(" (in native)")
    }

    info.stackTrace.let { printStackTrace(builder, it, linkInfoList) }
}

fun ThreadInfo.isSignificant(vararg packagesToSkip: String = arrayOf("java", "sun", "com.sun")): Boolean {
    return stackTrace?.any {
        stackTrace ->
        packagesToSkip.all { !stackTrace.className.startsWith(it) }
    } ?: false
}

fun ThreadInfo.isAWTThread() = threadName.startsWith("AWT-EventQueue")

fun ThreadInfo.isPerformingRunReadAction(): Boolean {
    return stackTrace?.any { it?.methodName?.contains("runReadAction", ignoreCase = true) ?: false } ?: false
}

fun ThreadDumpInfo.getBlockingThreadNames(): List<String>? {
    return threadInfos.filter { it.isPerformingRunReadAction() }.map { it.threadName }
}

fun createHyperLinks(linkInfoList: List<LinkInfo>, project: Project) {
    val javaPsiFacade = JavaPsiFacade.getInstance(project)
    val fileEditorManager = FileEditorManager.getInstance(project)
    val editorHyperlinkSupport = EditorHyperlinkSupport(fileEditorManager.selectedTextEditor!!, project)

    for (info in linkInfoList) {
        val psiClass = javaPsiFacade.findClass(info.className, GlobalSearchScope.allScope(project))
        val containingFile = psiClass?.containingFile?.run {
            JavaEditorFileSwapper.findSourceFile(project, virtualFile) ?: virtualFile
        }

        containingFile?.let {
            val openFileHyperlinkInfo = OpenFileHyperlinkInfo(project, it, info.lineNumber - 1)

            editorHyperlinkSupport.createHyperlink(info.highlightStartOffset, info.highlightEndOffset,
                    LinkInfo.HighlightInfo.color, openFileHyperlinkInfo)
        }
    }
}

fun main(args: Array<String>) {
    val allThreadDumps = ThreadDumpDaoMongo().getAllThreadDumps()

    println(createFileContent(allThreadDumps[3]).text)
}