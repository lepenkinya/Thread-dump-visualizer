package org.compscicenter.typingFreezeAnalyzer

import com.intellij.codeEditor.JavaEditorFileSwapper
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.impl.EditorHyperlinkSupport
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
import com.intellij.uml.UmlGraphBuilderFactory
import java.awt.Color
import java.awt.Font
import java.lang.management.ThreadInfo
import java.util.*
import javax.swing.JComponent

fun getReadableState(state: Thread.State) = when (state) {
    Thread.State.BLOCKED -> "blocked"
    Thread.State.TIMED_WAITING, Thread.State.WAITING -> "waiting on condition"
    Thread.State.RUNNABLE -> "runnable"
    Thread.State.NEW -> "new"
    Thread.State.TERMINATED -> "terminated"
}

fun printStackTrace(info: ThreadInfo,
                    text: StringBuilder,
                    classLinkInfoList: MutableList<ClassLinkInfo>,
                    highlightInfoList: MutableList<HighlightInfo>) {
    info.stackTrace?.forEach {
        text.appendln("    at $it")

        if (it.isPerformingRunReadAction()) highlightRunReadAction(info, it, text, highlightInfoList)
        if (it.isResolvable()) addClassLinkInfo(info, it, text, classLinkInfoList)
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

    if (info.isSuspended) text.appendln(" (suspended)")
    if (info.isInNative) text.appendln(" (in native)")

    printStackTrace(info, text, classLinkInfoList, highlightInfoList)

    text.appendln()
}

fun addClassLinkInfo(info: ThreadInfo,
                     element: StackTraceElement,
                     text: StringBuilder,
                     classLinkInfoList: MutableList<ClassLinkInfo>) {
    val startOffset = text.length - 3 - "${element.lineNumber}".length - element.fileName.length
    val endOffset = text.length - 2
    val textAttributes = TextAttributes(JBColor.CYAN, null, JBColor.CYAN, EffectType.LINE_UNDERSCORE, Font.BOLD)
    val highlightInfo = HighlightInfo(info, startOffset, endOffset, textAttributes, HighlightType.LINK)
    val classLinkInfo = ClassLinkInfo(element.className.substringBefore('$'), element.lineNumber, highlightInfo)

    classLinkInfoList += classLinkInfo
}

fun highlightRunReadAction(info: ThreadInfo,
                           element: StackTraceElement,
                           text: StringBuilder,
                           highlightInfoList: MutableList<HighlightInfo>) {
    val startOffset = text.length - "$element".length - 1
    val endOffset = text.length - 4 - "${element.lineNumber}".length - element.fileName.length
    val textAttributes = TextAttributes(JBColor.RED, null, JBColor.RED, EffectType.WAVE_UNDERSCORE, Font.BOLD)

    highlightInfoList += HighlightInfo(info, startOffset, endOffset, textAttributes, HighlightType.READ_ACTION)
}

fun highlightThreadState(info: ThreadInfo,
                         text: StringBuilder,
                         highlightInfoList: MutableList<HighlightInfo>) {
    val startOffset = text.length - "${info.threadState}".length
    val endOffset = text.length
    val textAttributes = TextAttributes(info.getStateColor(), null, null, null, Font.BOLD)

    highlightInfoList += HighlightInfo(info, startOffset, endOffset, textAttributes, HighlightType.THREAD_STATE)
}


fun ThreadInfo.getStateColor(): Color = when (threadState) {
    Thread.State.BLOCKED, Thread.State.WAITING, Thread.State.TIMED_WAITING -> Color.RED
    Thread.State.RUNNABLE -> if (isYielding()) Color.YELLOW else Color.GREEN
    else -> Color.GREEN
}

fun ThreadInfo.isSignificant(vararg packagesToSkip: String = arrayOf("java", "sun", "com.sun")) = when (stackTrace) {
    null -> false
    else -> stackTrace.asSequence()
            .filter { it.className != null }
            .any { stackTrace -> packagesToSkip.none { stackTrace.className.startsWith(it) } }
}

fun ThreadInfo.weight() = when (getStateColor()) {
    Color.RED -> 3
    Color.YELLOW -> 2
    Color.GREEN -> 1
    else -> 0
}

fun ThreadInfo.isPerformingRunReadAction() = when (stackTrace) {
    null -> false
    else -> stackTrace.asSequence().filter { it.methodName != null }.any { it.isPerformingRunReadAction() }
}

fun ThreadInfo.isYielding() = when {
    stackTrace == null || stackTrace.isEmpty() || stackTrace[0].methodName == null -> false
    else -> "yield" in stackTrace[0].methodName
}
fun ThreadInfo.isAWTThread() = threadName.startsWith("AWT-EventQueue")
fun ThreadDumpInfo.findThreadById(id: Long) = threadInfos.find { it.threadId == id }
fun ThreadDumpInfo.getBlockingThreads() = threadInfos.filter {
    it.isPerformingRunReadAction() && ((it.threadState == Thread.State.RUNNABLE && !it.isYielding()) || it.lockOwnerId != -1L)
}
fun ThreadDumpInfo.getBlockingThreadNames() = getBlockingThreads().map { it.threadName }
fun StackTraceElement.isResolvable() = className != null && fileName != null && !isNativeMethod && lineNumber >= 0
fun StackTraceElement.isPerformingRunReadAction() = methodName.contains("runReadAction", ignoreCase = true)

fun findFile(project: Project, filename: String): VirtualFile? {
    val javaPsiFacade = JavaPsiFacade.getInstance(project)
    val psiFile = javaPsiFacade.findClass(filename, GlobalSearchScope.allScope(project))?.containingFile

    return psiFile?.run { JavaEditorFileSwapper.findSourceFile(project, virtualFile) ?: virtualFile }
}

fun enrichFile(project: Project, fileContent: FileContent) {
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

            with(info.highlightInfo) {
                editorHyperlinkSupport.createHyperlink(startOffset, endOffset, textAttributes, openFileHyperlinkInfo)
            }
        }
    }
}

fun MarkupModel.addRangeHighlighter(highlightInfo: HighlightInfo) {
    with(highlightInfo) {
        addRangeHighlighter(startOffset, endOffset, HighlighterLayer.SYNTAX, textAttributes, targetArea)
    }
}

fun addHighlighters(fileEditor: Editor, highlightInfoList: List<HighlightInfo>) {
    val markupModel = fileEditor.markupModel

    highlightInfoList.forEach { markupModel.addRangeHighlighter(it) }
}

fun ThreadDumpInfo.getDependencyGraph(): List<Pair<ThreadInfo, ThreadInfo>> {
    val blockingThreads = getBlockingThreads()

    val dependencyGraph = ArrayList<Pair<ThreadInfo, ThreadInfo>>().apply {
        addAll(blockingThreads.map { awtThread to it })
        addAll(blockingThreads.filter { it.lockOwnerId != -1L }.map { it to findThreadById(it.lockOwnerId)!! })
    }

    return dependencyGraph
}

fun FileContent.getThreadStateOffset(threadId: Long) = highlightInfoList.asSequence()
        .filter { it.threadInfo.threadId == threadId }
        .find { it.highlightType == HighlightType.THREAD_STATE }?.startOffset

fun FileContent.getReadActionOffset(threadId: Long) = highlightInfoList.asSequence()
        .filter { it.threadInfo.threadId == threadId }
        .find { it.highlightType == HighlightType.READ_ACTION }?.startOffset

fun createFileContent(dumpInfo: ThreadDumpInfo): FileContent {
    val text = StringBuilder()
    val linkInfoList = ArrayList<ClassLinkInfo>()
    val highlightInfoList = ArrayList<HighlightInfo>()

    dumpInfo.threadInfos
            .asSequence()
            .filter { it.isSignificant() }
            .forEach { dumpThreadInfo(it, text, linkInfoList, highlightInfoList) }

    return FileContent("$text", linkInfoList, highlightInfoList)
}

fun createDiagramComponent(project: Project,
                           file: VirtualFile,
                           dumpInfo: ThreadDumpInfo,
                           fileContent: FileContent): JComponent {
    val stringDiagramProvider = ThreadInfoDiagramProvider(project, file, dumpInfo.getDependencyGraph(), fileContent)
    val builder = UmlGraphBuilderFactory.create(project, stringDiagramProvider, null, null).apply {
        update()
        view.fitContent()
        view.updateView()
        dataModel.setModelInitializationFinished()
    }

    return builder.view.jComponent
}

fun main(args: Array<String>) {
    ThreadDumpDaoMongo().getAllThreadDumps().forEach {
        println(createFileContent(it).text)
    }
}