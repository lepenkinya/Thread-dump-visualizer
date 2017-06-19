package org.compscicenter.threadDumpVisualizer

import com.intellij.ide.dnd.DnDDropHandler
import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDSupport
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*
import javax.swing.tree.*

class ToolWindow : ToolWindowFactory {
    var panel: JPanel
    var tree: JTree

    init {
        val root = DefaultMutableTreeNode("Thread dumps")

        tree = JTree(root).apply {
            expandPath(TreePath(model.root))
            cellRenderer = ThreadDumpTreeCellRenderer
        }

        panel = JPanel(BorderLayout()).apply {
            val jScrollPane = JScrollPane(tree).apply { border = BorderFactory.createEmptyBorder() }

            add(jScrollPane)
        }

        DnDSupport.createBuilder(tree)
                .setDropHandler(FileDropHandler(panel, tree))
                .enableAsNativeTarget()
                .disableAsSource()
                .install()
    }

    override fun init(toolWindow: ToolWindow) {
        super.init(toolWindow)

        toolWindow.apply { isAutoHide = false }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        tree.apply {
            addMouseListener(ThreadDumpMouseAdapter(this, project))
            addKeyListener(ThreadDumpKeyAdapter(this, project))
        }

        toolWindow.contentManager.apply { addContent(factory.createContent(panel, "", false)) }
    }
}

class FileDropHandler(val panel: JPanel,
                      val tree: JTree) : DnDDropHandler {

    fun createNode(file: File) = when (file.extension) {
        "dbconf" -> createNodeFromMongo(file)
        "zip" -> createNodeFromZip(file)
        "txt" -> createNodeFromTxt(file)
        else -> throw DnDException("Unknown file extension: ${file.extension}")
    }

    override fun drop(event: DnDEvent) {
        val application = ApplicationManager.getApplication()
        val files = getTransferable(event)?.getFiles(event)

        application.apply {
            executeOnPooledThread {
                if (files == null) throw DnDException("Can't get files")
                val model = tree.model as DefaultTreeModel
                val root = model.root as DefaultMutableTreeNode

                try {
                    files.forEach { root.add(createNode(it)) }
                    model.reload()
                } catch (e: Exception) {
                    val jPanel = JTextPane().apply {
                        text = when (e) {
                            is DnDException -> "${e.message}"
                            else -> "Unknown exception: ${e.message}"
                        }
                        isEditable = false
                    }

                    invokeLater {
                        JBPopupFactory.getInstance()
                                .createComponentPopupBuilder(jPanel, jPanel)
                                .createPopup()
                                .showInCenterOf(panel)
                    }
                }
            }
        }
    }
}

object ThreadDumpTreeCellRenderer : DefaultTreeCellRenderer() {
    override fun getTreeCellRendererComponent(tree: JTree?,
                                              value: Any?,
                                              selected: Boolean,
                                              expanded: Boolean,
                                              leaf: Boolean,
                                              row: Int,
                                              hasFocus: Boolean): Component {
        val renderer = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
        val info = (value as? DefaultMutableTreeNode)?.userObject as? Dump

        if (info != null) {
            val iconColor = info.awtThread.getStateColor().stringName()
            val iconName = "$iconColor-circle-16.png"
            val resource = javaClass.classLoader.getResource(iconName)

            if (resource != null) icon = ImageIcon(resource)
        }

        return renderer
    }
}

class ThreadDumpKeyAdapter(private val jTree: JTree,
                           private val project: Project) : KeyAdapter() {
    override fun keyPressed(e: KeyEvent) {
        val selectionPaths = jTree.selectionPaths ?: return

        when (e.keyCode) {
            VK_BACK_SPACE, VK_DELETE -> {
                val root = jTree.model.root as TreeNode
                val model = jTree.model as DefaultTreeModel

                selectionPaths.asSequence()
                        .map { it.lastPathComponent as TreeNode }
                        .map { findNodeToRemove(it, root) }
                        .filter { it.parent != null }
                        .forEach { model.removeNodeFromParent(it as MutableTreeNode) }
            }
            VK_ENTER -> {
                selectionPaths.asSequence()
                        .map { it.lastPathComponent as DefaultMutableTreeNode }
                        .mapNotNull { it.userObject as? Dump }
                        .forEach { openThreadDump(project, it) }
            }
        }
    }
}

class ThreadDumpMouseAdapter(private val jTree: JTree,
                             private val project: Project) : MouseAdapter() {
    override fun mouseClicked(e: MouseEvent) {
        val node = jTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val dumpInfo = node.userObject as? Dump ?: return

        if (e.clickCount == 2) openThreadDump(project, dumpInfo)
    }
}

class DnDException(message: String) : Exception(message)