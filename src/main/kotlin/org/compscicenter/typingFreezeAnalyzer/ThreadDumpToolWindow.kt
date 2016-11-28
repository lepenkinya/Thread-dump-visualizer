package org.compscicenter.typingFreezeAnalyzer

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.ide.dnd.DnDDropHandler
import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDNativeTarget
import com.intellij.ide.dnd.DnDSupport
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.testFramework.LightVirtualFile
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.*
import java.io.File
import java.net.URL
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
            val jTextArea = JTextArea("Please select thread dump").apply { isEditable = false }

            add(jTextArea)
        }
    }

    fun createTree(): JComponent {
        val root = DefaultMutableTreeNode("MongoDB")

        dumps.forEach { root.add(DefaultMutableTreeNode(it)) }

        //todo very long initialization, 
        //todo general rule of thumb - if method is more than 10 lines of code, probably it's doing too much
        val jTree = JTree(root).apply {
            expandPath(TreePath(model.root))

            cellRenderer = object : DefaultTreeCellRenderer() {
                override fun getTreeCellRendererComponent(tree: JTree?,
                                                          value: Any?,
                                                          selected: Boolean,
                                                          expanded: Boolean,
                                                          leaf: Boolean,
                                                          row: Int,
                                                          hasFocus: Boolean): Component {
                    return super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus).apply {
                        val info = (value as? DefaultMutableTreeNode)?.userObject as? ThreadDumpInfo
                        info?.let {
                            val stateColor = it.awtThread.getStateColor()
                            val iconName = "${stateColor.stringName()}-circle-16.png"

                            icon = ImageIcon(javaClass.classLoader.getResource(iconName))
                        }
                    }
                }
            }

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val node = lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
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
            })
        }
        
        //todo To me this looks more straightforward
        //val pane = JScrollPane(jTree)
        //pane.border = BorderFactory.createEmptyBorder()
        
        return JScrollPane(jTree).apply { border = BorderFactory.createEmptyBorder() }
    }


    @Suppress("UNCHECKED_CAST")
    override fun drop(event: DnDEvent) {
        val transferable = when (event.attachedObject) {
            is DnDNativeTarget.EventInfo -> (event.attachedObject as DnDNativeTarget.EventInfo).transferable
            is DnDEvent -> event.attachedObject as DnDEvent
            else -> null
        } ?: return

        val fileList = event.transferDataFlavors.find { it == DataFlavor.javaFileListFlavor }
        val files = transferable.getTransferData(fileList) as? List<File> ?: return
        if (files.isEmpty()) return


        val file = files[0]
        if (file.extension == "dbconf") {
            val prop: Map<String, Any> = Jackson.mapper.readValue(file, object : TypeReference<HashMap<String, Any>>() {})
            dumps = ThreadDumpDaoMongo(prop).getAllThreadDumps().sortedByDescending {
                it.awtThread.weight()
            }
        }

//        val dataFlavor = event.transferDataFlavors?.find { it == DataFlavor.stringFlavor } ?: return
//        val path = transferable.getTransferData(dataFlavor) as? String ?: return
//        val file = File(URL(path).toURI())
//        if (file.extension != "dbconf") return


//        JBPopupFactory.getInstance()
//                .createComponentPopupBuilder(JPanel(), null)
//                .setAdText("TEST!!!")
//                .createPopup()
//                .showInFocusCenter()

        panel.remove(dropPanel)
        
        //todo here I think it's okay
        splitPane.apply {
            bottomComponent = createTree()
            topComponent = createSelectPane()
            isVisible = true
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