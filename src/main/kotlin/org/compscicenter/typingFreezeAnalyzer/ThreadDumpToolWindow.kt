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
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.GridBagLayout
import java.awt.event.*
import java.io.File
import java.util.*
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.TreePath

class ThreadDumpToolWindow : ToolWindowFactory {
    lateinit var panel: JPanel
    lateinit var splitPane: JSplitPane
    lateinit var dropPanel: JPanel

    override fun init(toolWindow: ToolWindow) {
        super.init(toolWindow)

        toolWindow.apply {
            component.addComponentListener(ToolWindowComponentListener(splitPane))
            isAutoHide = false
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val jTextArea = synchronized(dropPanel.treeLock) { dropPanel.components[0] as JComponent }

        DnDSupport.createBuilder(jTextArea)
                .setDropHandler(FileDropHandler(panel, dropPanel, splitPane, project))
                .enableAsNativeTarget()
                .disableAsSource()
                .install()

        toolWindow.contentManager.apply { addContent(factory.createContent(panel, "", false)) }
    }

    fun createUIComponents() {
        splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
            dividerSize = 5
            resizeWeight = 0.5
            isVisible = false

            addContainerListener(object : ContainerListener {
                override fun componentAdded(e: ContainerEvent) = reorganise(width, height)
                override fun componentRemoved(e: ContainerEvent) = Unit
            })
        }

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
            add(splitPane)
            add(dropPanel)
        }
    }
}

class FileDropHandler(val panel: JPanel,
                      val dropPanel: JPanel,
                      val splitPane: JSplitPane,
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
            addMouseListener(ThreadDumpMouseAdapter(this, splitPane, project))
        }

        val jScrollPane = JScrollPane(jTree).apply {
            border = BorderFactory.createEmptyBorder()
        }

        return jScrollPane
    }

    override fun drop(event: DnDEvent) {
        try {
            val file = getTransferable(event)?.getFile(event) ?: throw DnDException("Can't get file")

            splitPane.apply {
                bottomComponent = createTree(file)
                topComponent = createSelectPane()
                isVisible = true
            }

            panel.remove(dropPanel)
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

class ToolWindowComponentListener(val splitPane: JSplitPane) : ComponentListener {
    override fun componentResized(e: ComponentEvent) = with(e.component.bounds) { splitPane.reorganise(width, height) }
    override fun componentMoved(e: ComponentEvent) = Unit
    override fun componentShown(e: ComponentEvent) = Unit
    override fun componentHidden(e: ComponentEvent) = Unit
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
        }

        return renderer
    }
}

class ThreadDumpMouseAdapter(private val jTree: JTree,
                             private val splitPane: JSplitPane,
                             private val project: Project) : MouseAdapter() {
    override fun mouseClicked(e: MouseEvent) {
        val node = jTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val dumpInfo = node.userObject as? ThreadDumpInfo ?: return

        if (e.clickCount != 2) return

        val fileContent = createFileContent(dumpInfo)
        val file = LightVirtualFile("${dumpInfo.objectId}.txt", fileContent.text)
        val jTextArea = JTextArea("AWT thread waits: ${dumpInfo.getBlockingThreadNames().joinToString()}")

        with(FileEditorManager.getInstance(project)) {
            openFile(file, true)
            addTopComponent(getSelectedEditor(file)!!, jTextArea)
        }

        enrichFile(project, fileContent)
        splitPane.topComponent = createDiagramComponent(project, file, dumpInfo, fileContent)
    }
}

class DnDException(message: String) : Exception(message)