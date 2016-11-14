package org.compscicenter.typingFreezeAnalyzer

import com.intellij.codeEditor.JavaEditorFileSwapper
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.impl.EditorHyperlinkSupport
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import java.awt.Color
import java.awt.Font
import java.lang.management.ThreadInfo

fun getReadableState(state: Thread.State) = when (state) {
    Thread.State.BLOCKED -> "blocked"
    Thread.State.TIMED_WAITING, Thread.State.WAITING -> "waiting on condition"
    Thread.State.RUNNABLE -> "runnable"
    Thread.State.NEW -> "new"
    Thread.State.TERMINATED -> "terminated"
}

fun printStackTrace(text: StringBuilder,
                    stackTraceElements: Array<StackTraceElement>,
                    classLinkInfoList: MutableList<ClassLinkInfo>,
                    highlightInfoList: MutableList<HighlightInfo>) {
    stackTraceElements.take(20).forEach {
        text.appendln("    at $it")

        if (it.isPerformingRunReadAction()) {
            highlightRunReadAction(it, text, highlightInfoList)
        }
        if (it.isResolvable()) {
            addClassLinkInfo(it, text, classLinkInfoList)
        }
    }
}

fun dumpThreadInfo(info: ThreadInfo,
                   text: StringBuilder,
                   classLinkInfoList: MutableList<ClassLinkInfo>,
                   highlightInfoList: MutableList<HighlightInfo>) {
    text.appendln("\"${info.threadName}\" tid=${info.threadId} ${getReadableState(info.threadState)}")
    text.append("    java.lang.Thread.State: ${info.threadState}")

    highlightThreadState(info, text, highlightInfoList)

    info.lockName?.let { text.append(" on ${info.lockName}") }
    info.lockOwnerName?.let { text.append(" owned by \"${info.lockOwnerName}\" Id=${info.lockOwnerId}") }

    text.appendln()

    if (info.isSuspended) {
        text.append(" (suspended)")
        text.appendln()
    }

    if (info.isInNative) {
        text.append(" (in native)")
        text.appendln()
    }

    info.stackTrace?.let { printStackTrace(text, it, classLinkInfoList, highlightInfoList) }
    text.appendln()
}

fun addClassLinkInfo(element: StackTraceElement,
                     builder: StringBuilder,
                     classLinkInfoList: MutableList<ClassLinkInfo>) {
    val startOffset = builder.length - 3 - "${element.lineNumber}".length - element.fileName.length
    val endOffset = builder.length - 2
    val textAttributes = TextAttributes(Color.CYAN, null, Color.CYAN, EffectType.LINE_UNDERSCORE, Font.BOLD)
    val highlightInfo = HighlightInfo(startOffset, endOffset, textAttributes)
    val classLinkInfo = ClassLinkInfo(element.className.substringBefore("$"), element.lineNumber, highlightInfo)

    classLinkInfoList += classLinkInfo
}

fun highlightRunReadAction(element: StackTraceElement,
                           text: StringBuilder,
                           highlightInfoList: MutableList<HighlightInfo>) {
    val startOffset = text.length - "$element".length - 1
    val endOffset = text.length - 4 - "${element.lineNumber}".length - element.fileName.length
    val textAttributes = TextAttributes(Color.RED, null, Color.RED, EffectType.WAVE_UNDERSCORE, Font.BOLD)

    highlightInfoList += HighlightInfo(startOffset, endOffset, textAttributes)
}

fun highlightThreadState(info: ThreadInfo,
                         text: StringBuilder,
                         highlightInfoList: MutableList<HighlightInfo>) {
    val startOffset = text.length - "${info.threadState}".length
    val endOffset = text.length
    val textAttributes = TextAttributes(info.getStateColor(), null, null, null, Font.BOLD)

    highlightInfoList += HighlightInfo(startOffset, endOffset, textAttributes)
}

fun StackTraceElement.isResolvable() = className != null && fileName != null && !isNativeMethod && lineNumber >= 0

fun ThreadInfo.getStateColor(): Color = when (threadState) {
    Thread.State.BLOCKED, Thread.State.WAITING, Thread.State.TIMED_WAITING -> Color.RED
    else -> Color.GREEN
}

fun ThreadInfo.isSignificant(vararg packagesToSkip: String = arrayOf("java", "sun", "com.sun")): Boolean {
    stackTrace ?: return false
    return stackTrace.asSequence()
            .filter { it.className != null }
            .any { stackTrace -> packagesToSkip.none { stackTrace.className.startsWith(it) } }
}

fun ThreadInfo.isAWTThread() = threadName.startsWith("AWT-EventQueue")

fun StackTraceElement.isPerformingRunReadAction() = methodName.contains("runReadAction", ignoreCase = true)

fun ThreadInfo.isPerformingRunReadAction(): Boolean {
    stackTrace ?: return false
    return stackTrace.asSequence()
            .filter { it.methodName != null }
            .any { it.isPerformingRunReadAction() }
}

fun ThreadDumpInfo.getBlockingThreadNames(): List<String>? {
    return threadInfos.filter { it.isPerformingRunReadAction() }.map { it.threadName }
}

fun findFile(project: Project, filename: String): VirtualFile? {
    val javaPsiFacade = JavaPsiFacade.getInstance(project)
    val psiClass = javaPsiFacade.findClass(filename, GlobalSearchScope.allScope(project))

    return psiClass?.containingFile?.run {
        JavaEditorFileSwapper.findSourceFile(project, virtualFile) ?: virtualFile
    }
}

fun enrichFile(fileContent: FileContent, project: Project) {
    val fileEditor = FileEditorManager.getInstance(project).selectedTextEditor!!

    createHyperLinks(project, fileEditor, fileContent.classLinkInfoList)
    addHighlighters(fileEditor, fileContent.highlightInfoList)
}

fun createHyperLinks(project: Project, fileEditor: Editor, classLinkInfoList: List<ClassLinkInfo>) {
    val editorHyperlinkSupport = EditorHyperlinkSupport(fileEditor, project)

    for (info in classLinkInfoList) {
        val containingFile = findFile(project, info.className)

        containingFile?.let {
            val openFileHyperlinkInfo = OpenFileHyperlinkInfo(project, it, info.lineNumber - 1)

            editorHyperlinkSupport.createHyperlink(info.highlightInfo.startOffset, info.highlightInfo.endOffset,
                    info.highlightInfo.textAttributes,
                    openFileHyperlinkInfo)
        }
    }
}

fun addHighlighters(fileEditor: Editor, highlightInfoList: List<HighlightInfo>) {
    val markupModel = fileEditor.markupModel

    highlightInfoList.forEach {
        markupModel.addRangeHighlighter(it.startOffset, it.endOffset, HighlighterLayer.SYNTAX, it.textAttributes, it.targetArea)
    }
}

fun main(args: Array<String>) {
    val allThreadDumps = ThreadDumpDaoMongo().getAllThreadDumps()
    allThreadDumps.forEach { println(createFileContent(it).text) }
}