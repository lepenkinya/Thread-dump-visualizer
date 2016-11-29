package org.compscicenter.typingFreezeAnalyzer

import com.fasterxml.jackson.core.type.TypeReference
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
import java.awt.event.*
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
        //why it is synchronized?
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

    lateinit var dumps: List<ThreadDumpInfo>

    fun createSelectPane(): JPanel {
        return JPanel(GridBagLayout()).apply {
            val jTextArea = JTextArea("Please select thread dump").apply {
                isEditable = false
            }

            add(jTextArea)
        }
    }

    fun createTree(): JComponent {
        val root = DefaultMutableTreeNode("MongoDB")

        dumps.forEach { root.add(DefaultMutableTreeNode(it)) }

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
            if (file.extension != "dbconf") throw DnDException("Wrong file extension")

            val prop: Map<String, Any> = Jackson.mapper.readValue(file, object : TypeReference<HashMap<String, Any>>() {})
            val mongoConfig = MongoConfig(prop)

            dumps = ThreadDumpDaoMongo(mongoConfig).getAllThreadDumps().sortedByDescending { it.awtThread.weight() }

            panel.remove(dropPanel)

            splitPane.apply {
                bottomComponent = createTree()
                topComponent = createSelectPane()
                isVisible = true
            }
        } catch (e: Exception) {
            val jPanel = JTextPane().apply {
                text = if (e is DnDException) "${e.message}" else "Something went wrong"
                isEditable = false
            }

            JBPopupFactory.getInstance()
                    .createComponentPopupBuilder(jPanel, jPanel)
                    .createPopup()
                    .showInCenterOf(panel)
        }
    }
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
            val stateColor = info.awtThread.getStateColor()
            val iconName = "${stateColor.stringName()}-circle-16.png"
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