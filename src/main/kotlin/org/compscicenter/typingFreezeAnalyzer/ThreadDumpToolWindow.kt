package org.compscicenter.typingFreezeAnalyzer

import com.intellij.ide.dnd.DnDDropHandler
import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDSupport
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import org.compscicenter.typingFreezeAnalyzer.utils.*
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.GridBagLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
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

        toolWindow.apply { isAutoHide = false }
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
    fun createTree(file: File): JComponent {
        val node = when (file.extension) {
            "dbconf" -> createTreeFromMongo(file)
            "zip" -> createTreeFromZip(file)
            "txt" -> createTreeFromTxt(file)
            else -> throw DnDException("Unknown file extension: ${file.extension}")
        }

        val root = DefaultMutableTreeNode("Dumps").apply { add(node) }

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
            val tree = createTree(file)

            panel.apply {
                remove(dropPanel)
                add(tree)
                revalidate()
                repaint()
            }
        } catch (e: Exception) {
            val jPanel = JTextPane().apply {
                text = when (e) {
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

        if (e.clickCount == 2) openThreadDump(project, dumpInfo)
    }
}

class DnDException(message: String) : Exception(message)