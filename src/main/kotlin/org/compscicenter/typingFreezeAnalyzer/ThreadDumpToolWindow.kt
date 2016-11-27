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
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

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
        DnDSupport.createBuilder(dropPanel.components[0] as JComponent)
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
        val jTextArea = JTextArea("Please select thread dump").apply { isEditable = false }

        return JPanel(GridBagLayout()).apply { add(jTextArea) }
    }

    fun createTable(): JTable {
        val jTable = JTable(object : AbstractTableModel() {
            override fun getColumnName(column: Int) = "Thread dump id"
            override fun getRowCount() = dumps.size
            override fun getColumnCount() = 1
            override fun getValueAt(rowIndex: Int, columnIndex: Int) = "${dumps[rowIndex].objectId}"
        })

        jTable.apply {
            setDefaultRenderer(Any::class.java, ThreadDumpCellRenderer(dumps))
            addMouseListener(ThreadDumpTableMouseAdapter(splitPane, dumps, project))
        }

        return jTable
    }


    @Suppress("UNCHECKED_CAST")
    override fun drop(event: DnDEvent) {
        val transferable = when (event.attachedObject) {
            is DnDNativeTarget.EventInfo -> (event.attachedObject as DnDNativeTarget.EventInfo).transferable
            is DnDEvent -> event.attachedObject as DnDEvent
            else -> null
        } ?: return

        val dataFlavor = event.transferDataFlavors?.find { it == DataFlavor.stringFlavor } ?: return
        val path = transferable.getTransferData(dataFlavor) as? String ?: return
        val file = File(URL(path).toURI())
        if (file.extension != "dbconf") return

        try {
            val prop: Map<String, Any> = Jackson.mapper.readValue(file, object : TypeReference<HashMap<String, Any>>() {})

            dumps = ThreadDumpDaoMongo(prop).getAllThreadDumps().sortedByDescending {
                it.awtThread.weight()
            }
        } catch (e: Exception) {
            throw e // TODO create jpopup here
        }

//        JBPopupFactory.getInstance()
//                .createComponentPopupBuilder(JPanel(), null)
//                .setAdText("TEST!!!")
//                .createPopup()
//                .showInFocusCenter()

        panel.remove(dropPanel)

        splitPane.apply {
            bottomComponent = createTable()
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

class ThreadDumpCellRenderer(val dumps: List<ThreadDumpInfo>) : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(table: JTable,
                                               value: Any?,
                                               isSelected: Boolean,
                                               hasFocus: Boolean,
                                               row: Int,
                                               column: Int): Component {
        return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column).apply {
            foreground = dumps[row].awtThread.getStateColor()
        }
    }
}

class ThreadDumpTableMouseAdapter(val splitPane: JSplitPane,
                                  val dumps: List<ThreadDumpInfo>,
                                  val project: Project) : MouseAdapter() {
    fun getSelectedThreadDump(): ThreadDumpInfo {
        val table = splitPane.bottomComponent as JTable
        return dumps[table.selectedRow]
    }

    override fun mousePressed(e: MouseEvent) {
        if (e.clickCount != 2) return

        val dumpInfo = getSelectedThreadDump()
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