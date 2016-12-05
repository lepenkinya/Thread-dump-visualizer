package org.compscicenter.typingFreezeAnalyzer

import com.fasterxml.jackson.core.type.TypeReference
import com.intellij.codeEditor.JavaEditorFileSwapper
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.impl.EditorHyperlinkSupport
import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDNativeTarget
import com.intellij.ide.dnd.TransferableWrapper
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
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.io.IOUtils
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.*
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.tree.DefaultMutableTreeNode

fun getReadableState(state: Thread.State) = when (state) {
    Thread.State.BLOCKED -> "blocked"
    Thread.State.TIMED_WAITING, Thread.State.WAITING -> "waiting on condition"
    Thread.State.RUNNABLE -> "runnable"
    Thread.State.NEW -> "new"
    Thread.State.TERMINATED -> "terminated"
}

fun printStackTrace(info: ThreadInfoDigest,
                    text: StringBuilder,
                    classLinkInfoList: MutableList<ClassLinkInfo>,
                    highlightInfoList: MutableList<HighlightInfo>) {
    info.stackTrace?.forEach {
        text.appendln("    at $it")

        if (it.isPerformingRunReadAction()) highlightRunReadAction(info, it, text, highlightInfoList)
        if (it.isResolvable()) addClassLinkInfo(info, it, text, classLinkInfoList)
    }
}

fun dumpThreadInfo(info: ThreadInfoDigest,
                   text: StringBuilder,
                   classLinkInfoList: MutableList<ClassLinkInfo>,
                   highlightInfoList: MutableList<HighlightInfo>) {
    text.appendln("\"${info.threadName}\" tid=${info.threadId} ${getReadableState(info.threadState)}")
    text.append("    java.lang.Thread.State: ${info.threadState}")

    highlightThreadState(info, text, highlightInfoList)

    info.lockName?.let { text.append(" on ${info.lockName}") }
    info.lockOwnerName?.let { text.append(" owned by \"${info.lockOwnerName}\" Id=0x0") }

    text.appendln()

    if (info.suspended) text.appendln(" (suspended)")
    if (info.inNative) text.appendln(" (in native)")

    printStackTrace(info, text, classLinkInfoList, highlightInfoList)

    text.appendln()
}

fun addClassLinkInfo(info: ThreadInfoDigest,
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

fun highlightRunReadAction(info: ThreadInfoDigest,
                           element: StackTraceElement,
                           text: StringBuilder,
                           highlightInfoList: MutableList<HighlightInfo>) {
    val startOffset = text.length - "$element".length - 1
    val endOffset = text.length - 4 - "${element.lineNumber}".length - element.fileName.length
    val textAttributes = TextAttributes(JBColor.RED, null, JBColor.RED, EffectType.WAVE_UNDERSCORE, Font.BOLD)

    highlightInfoList += HighlightInfo(info, startOffset, endOffset, textAttributes, HighlightType.READ_ACTION)
}

fun highlightThreadState(info: ThreadInfoDigest,
                         text: StringBuilder,
                         highlightInfoList: MutableList<HighlightInfo>) {
    val startOffset = text.length - "${info.threadState}".length
    val endOffset = text.length
    val textAttributes = TextAttributes(info.getStateColor(), null, null, null, Font.BOLD)

    highlightInfoList += HighlightInfo(info, startOffset, endOffset, textAttributes, HighlightType.THREAD_STATE)
}


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

fun Color.stringName() = when (this) {
    Color.RED -> "red"
    Color.GREEN -> "green"
    Color.ORANGE -> "orange"
    else -> "undefined"
}

fun ThreadInfoDigest.isYielding() = when {
    stackTrace == null || stackTrace.isEmpty() || stackTrace[0].methodName == null -> false
    else -> "yield" in stackTrace[0].methodName
}

fun ThreadInfoDigest.isRunning() = threadState == Thread.State.RUNNABLE && !isYielding()

fun ThreadInfoDigest.isAWTThread() = threadName.startsWith("AWT-EventQueue")
fun ThreadDumpInfo.findThreadByName(threadName: String?) = threadInfos.find { it.threadName == threadName }
fun ThreadDumpInfo.getBlockingThreads() = threadInfos.filter {
    it.isPerformingRunReadAction() && (it.isRunning() || it.lockOwnerName != null)
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

fun ThreadDumpInfo.getDependencyGraph(): List<Pair<ThreadInfoDigest, ThreadInfoDigest>> {
    val blockingThreads = getBlockingThreads()

    val dependencyGraph = ArrayList<Pair<ThreadInfoDigest, ThreadInfoDigest>>().apply {
        with(awtThread) { if (lockOwnerName != null) add(this to findThreadByName(lockOwnerName)!!) }
        addAll(blockingThreads.map { awtThread to it })
        addAll(blockingThreads.filter { it.lockOwnerName != null }.map { it to findThreadByName(it.lockOwnerName)!! })
    }

    return dependencyGraph
}

fun FileContent.getThreadStateOffset(threadName: String) = highlightInfoList.asSequence()
        .filter { it.threadInfo.threadName == threadName }
        .find { it.highlightType == HighlightType.THREAD_STATE }?.startOffset

fun FileContent.getReadActionOffset(threadName: String) = highlightInfoList.asSequence()
        .filter { it.threadInfo.threadName == threadName }
        .find { it.highlightType == HighlightType.READ_ACTION }?.startOffset

fun createFileContent(dumpInfo: ThreadDumpInfo): FileContent {
    val text = StringBuilder()
    val linkInfoList = ArrayList<ClassLinkInfo>()
    val highlightInfoList = ArrayList<HighlightInfo>()

    dumpInfo.threadInfos
            .asSequence()
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

fun getTransferable(event: DnDEvent): Transferable? {
    return when (event.attachedObject) {
        is DnDNativeTarget.EventInfo -> (event.attachedObject as DnDNativeTarget.EventInfo).transferable
        is TransferableWrapper -> event
        else -> null
    }
}

fun Transferable.getFile(event: DnDEvent): File? {
    val dataFlavor = event.transferDataFlavors.find { it == DataFlavor.javaFileListFlavor } ?: return null
    val files = getTransferData(dataFlavor) as? List<*>

    return files?.firstOrNull() as? File
}

fun createSelectPane(): JPanel {
    return JPanel(GridBagLayout()).apply {
        val jTextArea = JTextArea("Please select thread dump").apply {
            isEditable = false
        }

        add(jTextArea)
    }
}

fun createTreeFromMongo(file: File): DefaultMutableTreeNode {
    val root = DefaultMutableTreeNode("MongoDB")
    val prop = FileDropHandler.Jackson.mapper.readValue<Map<String, Any>>(file, object : TypeReference<Map<String, Any>>() {})
    val mongoConfig = MongoConfig(prop)
    val dumps = ThreadDumpDaoMongo(mongoConfig).getAllThreadDumps().sortedByDescending { it.awtThread.weight() }

    dumps.forEach { root.add(DefaultMutableTreeNode(it)) }

    return root
}

fun createTreeFromZip(file: File): DefaultMutableTreeNode {
    val root = DefaultMutableTreeNode(file.name)
    val dirs = HashMap<String, DefaultMutableTreeNode>()

    ZipFile(file).use { zipFile ->
        val entries = zipFile.entries.toList().sortedBy { it.name }

        for (entry in entries) {
            val entryFile = File(entry.name)
            val parentNode = dirs[entryFile.parent] ?: root

            if (entry.isDirectory) {
                val newDir = DefaultMutableTreeNode(entryFile.name)

                parentNode.add(newDir)
                dirs[entryFile.path] = newDir
            } else {
                val dump = zipFile.getInputStream(entry).getThreadDump()

                if (dump != null) parentNode.add(DefaultMutableTreeNode(dump))
            }
        }
    }

    return root
}

fun createTreeFromTxt(file: File): DefaultMutableTreeNode {
    val dump = FileInputStream(file).getThreadDump() ?: throw DnDException("Can't parse dump from $file")
    return DefaultMutableTreeNode(dump)
}

fun InputStream.getThreadDump() = buffered().use {
    val fileContent = IOUtils.toString(it, "UTF-8")
    parseThreadDumpInfo(fileContent)
}

fun JSplitPane.reorganise(w: Int, h: Int) {
    dividerLocation = h / 2

    val topSize = Dimension(w, dividerLocation)
    val bottomSize = Dimension(w, h - dividerLocation)

    size = Dimension(w, h)
    bottomComponent?.apply {
        minimumSize = bottomSize
        size = bottomSize
    }
    topComponent?.apply {
        minimumSize = topSize
        size = topSize
    }
}