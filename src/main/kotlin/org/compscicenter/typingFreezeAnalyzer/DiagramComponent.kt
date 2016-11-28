package org.compscicenter.typingFreezeAnalyzer

import com.intellij.diagram.*
import com.intellij.diagram.components.DiagramNodeContainer
import com.intellij.diagram.extras.DiagramExtras
import com.intellij.diagram.extras.EditNodeHandler
import com.intellij.diagram.presentation.DiagramState
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Point
import java.awt.Shape
import java.lang.management.ThreadInfo
import java.util.*
import javax.swing.JComponent
import javax.swing.JPanel

object ThreadInfoDiagramRelationship : DiagramRelationshipInfoAdapter(null) {
    override fun getStartArrow(): Shape = DiagramRelationshipInfo.DIAMOND
}

object ThreadInfoVfsResolver : DiagramVfsResolver<ThreadInfo> {
    override fun getQualifiedName(element: ThreadInfo): String = element.threadName
    override fun resolveElementByFQN(fqn: String?, project: Project?) = null
}

object ThreadInfoDiagramElementManager : AbstractDiagramElementManager<ThreadInfo>() {
    override fun getNodeTooltip(element: ThreadInfo) = null
    override fun findInDataContext(context: DataContext?) = null
    override fun isAcceptableAsNode(element: Any?) = element != null
    override fun getElementTitle(element: ThreadInfo): String = if (element.isAWTThread()) "AWT" else "${element.threadId}"
    override fun getItemName(element: Any?, presentation: DiagramState?) = null
}

object ThreadInfoDiagramColorManager : DiagramColorManagerBase() {
    override fun getNodeHeaderColor(builder: DiagramBuilder?, node: DiagramNode<*>?): Color {
        val threadInfo = (node?.identifyingElement as? ThreadInfo) ?: return JBColor.RED

        return threadInfo.getStateColor()
    }
}

class ThreadInfoDiagramExtras(val project: Project,
                              val file: VirtualFile,
                              val fileContent: FileContent) : DiagramExtras<ThreadInfo>() {
    override fun getEditNodeHandler(): EditNodeHandler<ThreadInfo> {
        return EditNodeHandler { diagramNode, diagramPresentationModel ->
            val threadId = diagramNode.identifyingElement.threadId
            val offset = fileContent.run { getReadActionOffset(threadId) ?: getThreadStateOffset(threadId) ?: 0 }
            val editorIsOpen = FileEditorManager.getInstance(project).getSelectedEditor(file) != null

//            PropertiesComponent.getInstance().setValue("dump.viewer.last.path", "")

            OpenFileDescriptor(project, file, offset).navigate(true)
            if (!editorIsOpen) enrichFile(project, fileContent)
        }
    }

    override fun createNodeComponent(node: DiagramNode<ThreadInfo>?,
                                     builder: DiagramBuilder?,
                                     basePoint: Point?,
                                     wrapper: JPanel?): JComponent {
        return (super.createNodeComponent(node, builder, basePoint, wrapper) as DiagramNodeContainer).apply {
            // TODO rewrite this hack ( but maybe it is ok? )
            synchronized(treeLock) {
                (header.components[0] as JComponent).components[0].foreground = Color.BLACK
            }
        }
    }
}


class ThreadDumpPresentation {
    val threadName = "AWT"
    val onClick = Runnable { println("click") }
    
    
    /**
     * Probably this is all you need to pass to DiagramModel
     * And list of dependencies from ThreadDumpPresentation to ThreadDumpPresentation
     */
}

//do not use pair, most of the times it becomes confusing
class ThreadDumpDependency(val working: ThreadDumpPresentation, val waiting: ThreadDumpPresentation)


//todo like even here diagram should not know anything about ThreadInfo, 
//todo it just shows dependencies, and make navigation available
class ThreadInfoDiagramDataModel(project: Project,
                                 provider: DiagramProvider<ThreadInfo>,
                                 dependencies: List<Pair<ThreadInfo, ThreadInfo>>) : DiagramDataModel<ThreadInfo>(project, provider) {
    val nodesMap = HashMap<String, ThreadInfoNode>()
    val edgeList = ArrayList<ThreadInfoEdge>().apply {
        dependencies.forEach {
            val (blocked, blocking) = it
            val x = nodesMap.getOrPut(blocked.threadName, { ThreadInfoNode(blocked, provider) })
            val y = nodesMap.getOrPut(blocking.threadName, { ThreadInfoNode(blocking, provider) })

            add(ThreadInfoEdge(x, y))
        }
    }

    override fun dispose() = Unit
    override fun getNodes() = nodesMap.values
    override fun getEdges() = edgeList
    override fun getNodeName(node: DiagramNode<ThreadInfo>): String = node.identifyingElement.threadName
    override fun addElement(element: ThreadInfo) = null
    override fun refreshDataModel() = Unit
    override fun getModificationTracker(): ModificationTracker = NEVER_CHANGED
}

class ThreadInfoNode(val threadInfo: ThreadInfo,
                     provider: DiagramProvider<ThreadInfo>) : DiagramNodeBase<ThreadInfo>(provider) {
    override fun getIcon() = null
    override fun getTooltip() = null
    override fun getIdentifyingElement() = threadInfo
}

class ThreadInfoEdge(source: ThreadInfoNode,
                     target: ThreadInfoNode) : DiagramEdgeBase<ThreadInfo>(source, target, ThreadInfoDiagramRelationship)

class ThreadInfoDiagramProvider(val project: Project,
                                val file: VirtualFile,
                                val dependencies: List<Pair<ThreadInfo, ThreadInfo>>,
                                val fileContent: FileContent) : BaseDiagramProvider<ThreadInfo>() {
    val PROVIDER_ID = "ThreadInfoDiagramProvider"
    override fun getID() = PROVIDER_ID
    override fun getPresentableName(): String = "My ID"
    override fun getElementManager() = ThreadInfoDiagramElementManager
    override fun getVfsResolver() = ThreadInfoVfsResolver
    override fun getColorManager() = ThreadInfoDiagramColorManager
    override fun getExtras() = ThreadInfoDiagramExtras(project, file, fileContent)
    override fun createDataModel(project: Project,
                                 element: ThreadInfo?,
                                 file: VirtualFile?,
                                 presentationModel: DiagramPresentationModel?): DiagramDataModel<ThreadInfo> {
        return ThreadInfoDiagramDataModel(project, this@ThreadInfoDiagramProvider, dependencies)
    }
}