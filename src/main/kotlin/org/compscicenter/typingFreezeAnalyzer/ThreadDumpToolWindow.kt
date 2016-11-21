package org.compscicenter.typingFreezeAnalyzer

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.testFramework.LightVirtualFile
import com.intellij.uml.UmlGraphBuilderFactory
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class ThreadDumpToolWindow : ToolWindowFactory {
    lateinit var panel: JPanel
    lateinit var table: JTable
    lateinit var splitPane: JSplitPane
    lateinit var dumps: List<ThreadDumpInfo>

    override fun init(window: ToolWindow?) {
        super.init(window)
        dumps = ThreadDumpDaoMongo().getAllThreadDumps().sortedByDescending { it.awtThread.getStateColor().weight() }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.clickCount != 2) return

                val (row, col) = table.selectedRow to table.selectedColumn
                val name = table.getValueAt(row, col) as String
                val dumpInfo = dumps[row]

                with(FileEditorManager.getInstance(project)) {
                    val fileContent = createFileContent(dumpInfo)
                    val file = LightVirtualFile("$name.txt", fileContent.text)
                    val jTextArea = JTextArea("AWT thread waits: ${dumpInfo.getBlockingThreadNames().joinToString()}")

                    openFile(file, true)
                    enrichFile(project, fileContent)
                    addTopComponent(getSelectedEditor(file)!!, jTextArea)

                    splitPane.topComponent = createDiagramComponent(project, file, dumpInfo, fileContent)
                    splitPane.dividerLocation = splitPane.size.height / 2
                }
            }
        })

        toolWindow.apply {
            contentManager.addContent(contentManager.factory.createContent(splitPane, "", false))
            isAutoHide = false
        }
    }

    fun createUIComponents() {
        panel = JPanel()

        table = JTable(object : AbstractTableModel() {
            override fun getColumnName(column: Int) = "Thread dump id"
            override fun getRowCount() = dumps.size
            override fun getColumnCount() = 1
            override fun getValueAt(rowIndex: Int, columnIndex: Int) = "${dumps[rowIndex].objectId}"
        })

        table.setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(table: JTable,
                                                       value: Any?,
                                                       isSelected: Boolean,
                                                       hasFocus: Boolean,
                                                       row: Int,
                                                       column: Int): Component {
                val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                return component.apply { foreground = dumps[row].awtThread.getStateColor() }
            }
        })

        splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, JTextArea("Please select thread dump"), table).apply {
            size = Dimension(200, 700)
            dividerSize = 5
            dividerLocation = size.height / 2
            resizeWeight = 0.5
        }
    }
}

fun createFileContent(dumpInfo: ThreadDumpInfo): FileContent {
    val text = StringBuilder()
    val linkInfoList = ArrayList<ClassLinkInfo>()
    val highlightInfoList = ArrayList<HighlightInfo>()

    dumpInfo.threadInfos
            .asSequence()
            .filter { it.isSignificant() }
            .filter {
                if (dumpInfo.isAWTThreadWaiting) {
                    it.isAWTThread() || it.isPerformingRunReadAction()
                } else {
                    true // TODO think about what to filter if AWT threadInfo has state running
                }
            }
            .forEach { dumpThreadInfo(it, text, linkInfoList, highlightInfoList) }

    return FileContent("$text", linkInfoList, highlightInfoList)
}

fun createDiagramComponent(project: Project,
                           file: VirtualFile,
                           dumpInfo: ThreadDumpInfo,
                           fileContent: FileContent): JComponent {
    val panel = JPanel().apply { layout = BorderLayout() }
    val stringDiagramProvider = StringDiagramProvider(project, file, dumpInfo.getDependencyGraph(), fileContent)
    val builder = UmlGraphBuilderFactory.create(project, stringDiagramProvider, null, null).apply {
        update()
        view.fitContent()
        view.updateView()
        dataModel.setModelInitializationFinished()
    }

    return panel.apply { add(builder.view.jComponent) }
}

fun main(args: Array<String>) {
}