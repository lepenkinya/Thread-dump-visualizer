package org.compscicenter.threadDumpVisualizer

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
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.uml.UmlGraphBuilderFactory
import org.apache.commons.compress.archivers.zip.ZipFile
import org.compscicenter.threadDumpVisualizer.mongo.ThreadDumpDaoMongo
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagLayout
import java.awt.datatransfer.Transferable
import java.io.File
import java.io.InputStream
import java.util.*
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeNode

val lineSeparatorLen = System.lineSeparator().length

fun getReadableState(state: Thread.State) = when (state) {
    Thread.State.BLOCKED                             -> "blocked"
    Thread.State.TIMED_WAITING, Thread.State.WAITING -> "waiting on condition"
    Thread.State.RUNNABLE                            -> "runnable"
    Thread.State.NEW                                 -> "new"
    Thread.State.TERMINATED                          -> "terminated"
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
    text.appendln("    java.lang.Thread.State: ${info.threadState}")

    highlightThreadState(info, text, highlightInfoList)

    info.lockName?.let { text.append(" on $it") }
    info.lockOwnerName?.let { text.append(" owned by \"$it\" Id=0x0") }

    if (info.suspended) text.append(" (suspended)")
    if (info.inNative) text.append(" (in native)")

    text.appendln()
    printStackTrace(info, text, classLinkInfoList, highlightInfoList)
    text.appendln()
}

fun addClassLinkInfo(info: ThreadInfoDigest,
                     element: StackTraceElement,
                     text: StringBuilder,
                     classLinkInfoList: MutableList<ClassLinkInfo>) {
    val startOffset = text.length - lineSeparatorLen - "${element.lineNumber}".length - element.fileName.length - 2
    val endOffset = text.length - lineSeparatorLen - 1
    val textAttributes = TextAttributes(JBColor.CYAN, null, JBColor.CYAN, EffectType.LINE_UNDERSCORE, Font.BOLD)
    val highlightInfo = HighlightInfo(info, startOffset, endOffset, textAttributes, HighlightType.LINK)
    val classLinkInfo = ClassLinkInfo(element.className.substringBefore('$'), element.lineNumber, highlightInfo)

    classLinkInfoList += classLinkInfo
}

fun highlightRunReadAction(info: ThreadInfoDigest,
                           element: StackTraceElement,
                           text: StringBuilder,
                           highlightInfoList: MutableList<HighlightInfo>) {
    val startOffset = text.length - "$element".length - lineSeparatorLen
    val endOffset = text.length - lineSeparatorLen - "${element.lineNumber}".length - element.fileName.length - 3
    val textAttributes = TextAttributes(JBColor.RED, null, JBColor.RED, EffectType.WAVE_UNDERSCORE, Font.BOLD)

    highlightInfoList += HighlightInfo(info, startOffset, endOffset, textAttributes, HighlightType.READ_ACTION)
}

fun highlightThreadState(info: ThreadInfoDigest,
                         text: StringBuilder,
                         highlightInfoList: MutableList<HighlightInfo>) {
    val startOffset = text.length - "${info.threadState}".length - lineSeparatorLen
    val endOffset = text.length - lineSeparatorLen
    val textAttributes = TextAttributes(info.getStateColor(), null, null, null, Font.BOLD)

    highlightInfoList += HighlightInfo(info, startOffset, endOffset, textAttributes, HighlightType.THREAD_STATE)
}


fun findFile(project: Project, filename: String): VirtualFile? {
    val javaPsiFacade = JavaPsiFacade.getInstance(project)
    val psiFile = javaPsiFacade.findClass(filename, GlobalSearchScope.allScope(project))?.containingFile

    return psiFile?.run { JavaEditorFileSwapper.findSourceFile(project, virtualFile) ?: virtualFile }
}

fun enrichFile(project: Project, editor: Editor, fileContent: FileContent) {
    createHyperLinks(project, editor, fileContent.classLinkInfoList)
    addHighlighters(editor, fileContent.highlightInfoList)
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

fun ThreadDumpInfo.createFileContent(): FileContent {
    val text = StringBuilder()
    val linkInfoList = ArrayList<ClassLinkInfo>()
    val highlightInfoList = ArrayList<HighlightInfo>()

    threadList.forEach { dumpThreadInfo(it, text, linkInfoList, highlightInfoList) }

    return FileContent("$text", linkInfoList, highlightInfoList)
}

fun createDiagramComponent(project: Project,
                           file: VirtualFile,
                           dumpInfo: ThreadDumpInfo,
                           fileContent: FileContent): JComponent {
    val stringDiagramProvider = ThreadInfoDiagramProvider(project, file, dumpInfo.getDependencyGraph(), fileContent)
    val builder = UmlGraphBuilderFactory.create(project, stringDiagramProvider, null, null).apply {
        update()
        view.apply {
            fitContent()
            updateView()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            fitContentOnResize = true
        }
        dataModel.setModelInitializationFinished()
    }

    return builder.view.jComponent
}

fun getTransferable(event: DnDEvent): Transferable? {
    return when (event.attachedObject) {
        is DnDNativeTarget.EventInfo -> (event.attachedObject as DnDNativeTarget.EventInfo).transferable
        is TransferableWrapper       -> event
        else                         -> null
    }
}


fun createNodeFromMongo(file: File): DefaultMutableTreeNode {
    val root = DefaultMutableTreeNode("MongoDB")

    try {
        val mapper = ObjectMapper()
        val prop = mapper.readValue<Map<String, Any>>(file, object : TypeReference<Map<String, Any>>() {})
        val mongoConfig = MongoConfig(prop)
        val mongoDao = ThreadDumpDaoMongo(mongoConfig)
        val dumps = mongoDao.getAllThreadDumps().sortedByDescending { it.awtThread.weight() }

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

fun openThreadDump(project: Project, dumpInfo: ThreadDumpInfo) {
    val fileContent = dumpInfo.createFileContent()
    val fileEditorManager = FileEditorManager.getInstance(project)
    val file = LightVirtualFile("$dumpInfo", PlainTextFileType.INSTANCE, fileContent.text)
    val textEditor = fileEditorManager.openReadOnly(file, false)
    val size = Dimension(600, 250)
    val jPanel = JPanel(GridBagLayout()).apply { preferredSize = size }
    val addContent = Runnable {
        val diagram = createDiagramComponent(project, file, dumpInfo, fileContent).apply { preferredSize = size }

        jPanel.add(diagram)
        enrichFile(project, textEditor.editor, fileContent)
    }

    fileEditorManager.addTopComponent(textEditor, jPanel)
    ApplicationManager.getApplication().invokeLater(addContent)
}

fun createNodeFromTxt(file: File): DefaultMutableTreeNode {
    val dump = try {
        //file.readText().parseThreadDump()
    } catch (e: Exception) {
        throw DnDException("Can't parse dump: ${e.message}")
    }

    return DefaultMutableTreeNode(dump)
}

fun InputStream.tryParseThreadDump(name: String = "") = try {
    Any()
} catch (e: Exception) {
    null
}