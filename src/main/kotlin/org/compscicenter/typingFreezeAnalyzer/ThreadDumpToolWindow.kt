package org.compscicenter.typingFreezeAnalyzer

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.testFramework.LightVirtualFile
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class ThreadDumpToolWindow : ToolWindowFactory {
    lateinit var panel: JPanel
    lateinit var table: JTable
    lateinit var splitPane: JSplitPane
    lateinit var dumps: List<ThreadDumpInfo>

    override fun init(window: ToolWindow?) {
        super.init(window)
        dumps = ThreadDumpDaoMongo().getAllThreadDumps()
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val (row, col) = table.selectedRow to table.selectedColumn
                    val name = table.getValueAt(row, col) as String
                    val dumpInfo = dumps[row]

                    with(FileEditorManager.getInstance(project)) {
                        val fileContent = createFileContent(dumpInfo)
                        val file = LightVirtualFile(name + ".txt", fileContent.text)
                        val jTextArea = JTextArea("AWT thread waits: ${dumpInfo.getBlockingThreadNames()?.joinToString()}")

                        openFile(file, true)
                        enrichFile(fileContent, project)
                        addTopComponent(getSelectedEditor(file)!!, jTextArea)
                    }
                }
            }
        })

        val contentManager = toolWindow.contentManager
        contentManager.addContent(contentManager.factory.createContent(splitPane, "", false))
        toolWindow.isAutoHide = false
    }

    fun createUIComponents() {
        panel = JPanel()

        table = JTable(object : AbstractTableModel() {
            override fun getColumnName(column: Int) = "Thread dump id"
            override fun getRowCount() = dumps.size
            override fun getColumnCount() = 1
            override fun getValueAt(rowIndex: Int, columnIndex: Int) = dumps[rowIndex].objectId.toString()
        })

        table.setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(table: JTable,
                                                       value: Any?,
                                                       isSelected: Boolean,
                                                       hasFocus: Boolean,
                                                       row: Int,
                                                       column: Int): Component {
                val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                c.foreground = if (dumps[row].isAWTThreadBlocked) Color.RED else Color.GRAY
                return c
            }
        })

        splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, JTextArea("This is place for diagram"), table)
        splitPane.size = Dimension(300, 700)
        splitPane.dividerSize = 5
        splitPane.dividerLocation = splitPane.size.height / 2
        splitPane.resizeWeight = 0.5
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
                if (dumpInfo.isAWTThreadBlocked) {
                    it.isAWTThread() || it.isPerformingRunReadAction()
                } else {
                    true // TODO think about what to filter if AWT thread has state running
                }
            }
            .forEach { dumpThreadInfo(it, text, linkInfoList, highlightInfoList) }

    return FileContent(text.toString(), linkInfoList, highlightInfoList)
}