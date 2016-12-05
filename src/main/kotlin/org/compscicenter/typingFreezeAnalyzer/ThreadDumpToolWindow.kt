package org.compscicenter.typingFreezeAnalyzer

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.ide.dnd.DnDDropHandler
import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDSupport
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.testFramework.LightVirtualFile
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.*
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class ThreadDumpToolWindow : ToolWindowFactory {
    lateinit var panel: JPanel
    lateinit var dropPanel: JPanel

    override fun init(toolWindow: ToolWindow) {
        super.init(toolWindow)

        toolWindow.apply {
            isAutoHide = false
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val jTextArea = synchronized(dropPanel.treeLock) { dropPanel.components[0] as JComponent }

        DnDSupport.createBuilder(jTextArea)
                .setDropHandler(FileDropHandler(panel, dropPanel, project))
                .enableAsNativeTarget()
                .disableAsSource()
                .install()

        toolWindow.contentManager.apply { addContent(factory.createContent(panel, "", false)) }
    }

    fun createUIComponents() {
        dropPanel = JPanel(GridBagLayout()).apply {
            val jTextArea = JTextArea(2, 16).apply {
                text = "DROP THREAD DUMP" +
                        "    FILE HERE   "
                lineWrap = true
                font = Font(font.fontName, Font.BOLD, 21)
                isEditable = false
            }

            add(jTextArea)
        }

        panel = JPanel(BorderLayout()).apply {
            add(dropPanel)
        }
    }
}

class FileDropHandler(val panel: JPanel,
                      val dropPanel: JPanel,
                      val project: Project) : DnDDropHandler {
    object Jackson {
        val mapper = ObjectMapper()
    }

    fun createTree(file: File): JComponent {
        val root = when (file.extension) {
            "dbconf" -> createTreeFromMongo(file)
            "zip" -> createTreeFromZip(file)
            "txt" -> createTreeFromTxt(file)
            else -> throw DnDException("Unknown file extension: ${file.extension}")
        }

        val jTree = JTree(root).apply {
            expandPath(TreePath(model.root))
            cellRenderer = ThreadDumpTreeCellRenderer
            addMouseListener(ThreadDumpMouseAdapter(this, project))
            addKeyListener(ThreadDumpKeyAdapter(this, project))
        }

        val jScrollPane = JScrollPane(jTree).apply {
            border = BorderFactory.createEmptyBorder()
        }

        return jScrollPane
    }

    override fun drop(event: DnDEvent) {
        try {
            val file = getTransferable(event)?.getFile(event) ?: throw DnDException("Can't get file")

            panel.apply {
                remove(dropPanel)
                add(createTree(file))
                isVisible = true
            }
        } catch (e: Exception) {
            val jPanel = JTextPane().apply {
                text = when (e) {
                    is NoSuchElementException -> "Bad database configuration"
                    is DnDException -> "${e.message}"
                    else -> "Unknown exception: ${e.message}"
                }
                isEditable = false
            }

            JBPopupFactory.getInstance()
                    .createComponentPopupBuilder(jPanel, jPanel)
                    .createPopup()
                    .showInCenterOf(panel)
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
        val info = (value as? DefaultMutableTreeNode)?.userObject as? ThreadDumpInfo

        if (info != null) {
            val iconColor = info.awtThread.getStateColor().stringName()
            val iconName = "$iconColor-circle-16.png"
            val resource = javaClass.classLoader.getResource(iconName)

            if (resource != null) icon = ImageIcon(resource)
        } else {
            icon = getOpenIcon()
        }

        return renderer
    }
}

fun openThreadDump(project: Project,
                   dumpInfo: ThreadDumpInfo) {
    val fileContent = createFileContent(dumpInfo)
    val file = LightVirtualFile("${dumpInfo.objectId}.txt", fileContent.text)

    with(FileEditorManager.getInstance(project)) {
        val diagram = createDiagramComponent(project, file, dumpInfo, fileContent).apply {
            size = Dimension(width, 300)
            preferredSize = Dimension(width, 300)
            maximumSize = Dimension(width, 300)
        }

        openFile(file, false)
        addTopComponent(getSelectedEditor(file)!!, diagram)
    }

    enrichFile(project, fileContent)
}

class ThreadDumpKeyAdapter(private val jTree: JTree,
                           private val project: Project) : KeyAdapter() {
    override fun keyPressed(e: KeyEvent) {
        val node = jTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val root = jTree.model.root

        if (node == root) return

        when (e.keyCode) {
            KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE -> {
                var nodeToRemove = node

                while (nodeToRemove.parent.childCount == 1 && nodeToRemove.parent != root) {
                    nodeToRemove = nodeToRemove.parent as DefaultMutableTreeNode
                }

                (jTree.model as DefaultTreeModel).removeNodeFromParent(nodeToRemove)
            }
            KeyEvent.VK_ENTER -> {
                val dumpInfo = node.userObject as? ThreadDumpInfo

                if (dumpInfo != null) openThreadDump(project, dumpInfo)
            }
        }
    }
}

class ThreadDumpMouseAdapter(private val jTree: JTree,
                             private val project: Project) : MouseAdapter() {
    override fun mouseClicked(e: MouseEvent) {
        val node = jTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val dumpInfo = node.userObject as? ThreadDumpInfo ?: return

        if (e.clickCount != 2) return

        openThreadDump(project, dumpInfo)
    }
}

class DnDException(message: String) : Exception(message)