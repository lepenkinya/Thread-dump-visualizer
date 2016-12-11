package org.compscicenter.typingFreezeAnalyzer.utils

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.codeEditor.JavaEditorFileSwapper
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.impl.EditorHyperlinkSupport
import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDNativeTarget
import com.intellij.ide.dnd.TransferableWrapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.uml.UmlGraphBuilderFactory
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.io.IOUtils
import org.compscicenter.typingFreezeAnalyzer.*
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagLayout
import java.awt.datatransfer.Transferable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.*
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeNode

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
    text.appendln("\"${info.threadName}\" ${getReadableState(info.threadState)}")
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

fun createHyperLinks(project: Project, editor: Editor, classLinkInfoList: List<ClassLinkInfo>) {
    val hyperlinkSupport = EditorHyperlinkSupport(editor, project)

    for (info in classLinkInfoList) {
        val containingFile = findFile(project, info.className) ?: continue
        val openFileHyperlinkInfo = OpenFileHyperlinkInfo(project, containingFile, info.lineNumber - 1)

        with(info.highlightInfo) {
            hyperlinkSupport.createHyperlink(startOffset, endOffset, textAttributes, openFileHyperlinkInfo)
        }
    }
}

fun addHighlighters(editor: Editor, highlightInfoList: List<HighlightInfo>) {
    val markupModel = editor.markupModel

    highlightInfoList.forEach { markupModel.addRangeHighlighter(it) }
}

fun createFileContent(dumpInfo: ThreadDumpInfo): FileContent {
    val text = StringBuilder()
    val linkInfoList = ArrayList<ClassLinkInfo>()
    val highlightInfoList = ArrayList<HighlightInfo>()

    dumpInfo.threadList.asSequence()
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


fun createNodeFromMongo(file: File): DefaultMutableTreeNode {
    val root = DefaultMutableTreeNode("MongoDB")

    try {
        val mapper = ObjectMapper()
        val prop = mapper.readValue<Map<String, Any>>(file, object : TypeReference<Map<String, Any>>() {})
        val mongoConfig = MongoConfig(prop)
        val dumps = ThreadDumpDaoMongo(mongoConfig).getAllThreadDumps().sortedByDescending { it.awtThread.weight() }

        dumps.forEach { root.add(DefaultMutableTreeNode(it)) }
    } catch (e: Exception) {
        throw DnDException("Can't load dumps from mongodb: ${e.message}")
    }

    return root
}

fun findNodeToRemove(node: TreeNode, root: TreeNode): TreeNode {
    var nodeToRemove = node

    while (with(nodeToRemove) { parent != null && parent != root && parent.childCount == 1 }) {
        nodeToRemove = nodeToRemove.parent
    }

    return nodeToRemove
}

fun getOrCreateParent(file: File,
                      dirs: MutableMap<String?, DefaultMutableTreeNode>): DefaultMutableTreeNode {
    val parent = file.parentFile

    return dirs.getOrPut(parent?.path, {
        val parentNode = DefaultMutableTreeNode(parent.name)

        getOrCreateParent(parent, dirs).add(parentNode)
        parentNode
    })
}

fun createNodeFromZip(file: File): DefaultMutableTreeNode {
    val root = DefaultMutableTreeNode(file.name)
    val dirs = HashMap<String?, DefaultMutableTreeNode>().apply { put(null, root) }

    try {
        ZipFile(file).use { zipFile ->
            val entries = zipFile.entries.toList().sortedBy { it.name }

            for (entry in entries) {
                val entryFile = File(entry.name)

                if (!entry.isDirectory) {
                    val dump = zipFile.getInputStream(entry).tryParseThreadDump(entryFile.name) ?: continue
                    val parentNode = getOrCreateParent(entryFile, dirs)
                    val node = DefaultMutableTreeNode(dump)

                    parentNode.add(node)
                }
            }
        }
    } catch (e: Exception) {
        throw DnDException("Can't parse zip file: ${e.message}")
    }

    return root
}

fun TextEditor.setViewerMode() {
    val editorEx = (editor as? EditorEx) ?: throw IllegalStateException("Editor is not instance of EditorEx")

    editorEx.isViewer = true
}

fun openThreadDump(project: Project,
                   dumpInfo: ThreadDumpInfo) {
    val fileContent = createFileContent(dumpInfo)
    val virtualFile = LightVirtualFile("$dumpInfo", PlainTextFileType.INSTANCE, fileContent.text)
    val fileEditorManager = FileEditorManager.getInstance(project)
    val fileEditor = fileEditorManager.openFile(virtualFile, false).single()
    val textEditor = (fileEditor as TextEditor).apply { setViewerMode() }
    val showDiagram = Runnable {
        val diagram = createDiagramComponent(project, virtualFile, dumpInfo, fileContent).apply {
            preferredSize = Dimension(600, 250)
            maximumSize = Dimension(600, 250)
        }

        fileEditorManager.addTopComponent(textEditor, JPanel(GridBagLayout()).apply { add(diagram) })
    }

    ApplicationManager.getApplication().executeOnPooledThread(showDiagram)
    enrichFile(project, fileContent)
}

fun createNodeFromTxt(file: File): DefaultMutableTreeNode {
    val dump = try {
        FileInputStream(file).parseThreadDump(file.name)
    } catch (e: Exception) {
        throw DnDException("Can't parse dump: ${e.message}")
    }

    return DefaultMutableTreeNode(dump)
}

fun InputStream.parseThreadDump(name: String = "") = buffered().use {
    val fileContent = IOUtils.toString(it, "UTF-8")

    fileContent.parseThreadDump(name)
}

fun InputStream.tryParseThreadDump(name: String = "") = try {
    parseThreadDump(name)
} catch (e: Exception) {
    null
}