package org.compscicenter.threadDumpVisualizer

import com.intellij.diagram.*
import com.intellij.diagram.components.DiagramNodeContainer
import com.intellij.diagram.extras.DiagramExtras
import com.intellij.diagram.extras.EditNodeHandler
import com.intellij.diagram.presentation.DiagramState
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import intellij.dumps.ThreadInfo
import java.awt.Color
import java.awt.Point
import java.awt.Shape
import java.util.*
import javax.swing.JComponent
import javax.swing.JPanel

object ThreadInfoDiagramRelationship : DiagramRelationshipInfoAdapter(null) {
    override fun getStartArrow(): Shape = DiagramRelationshipInfo.DIAMOND
}

object ThreadInfoVfsResolver : DiagramVfsResolver<ThreadPresentation> {
    override fun getQualifiedName(element: ThreadPresentation): String = element.name
    override fun resolveElementByFQN(fqn: String?, project: Project?) = null
}

object ThreadInfoDiagramElementManager : AbstractDiagramElementManager<ThreadPresentation>() {
    override fun getNodeTooltip(element: ThreadPresentation) = null
    override fun findInDataContext(context: DataContext?) = null
    override fun isAcceptableAsNode(element: Any?) = element != null
    override fun getItemName(element: Any?, presentation: DiagramState?) = null
    override fun getElementTitle(element: ThreadPresentation) = when {
        element.name.isAWTThreadName() -> "AWT"
        element.name == "No problems found" -> element.name
        else -> element.name.initials()
    }
}

object ThreadInfoDiagramColorManager : DiagramColorManagerBase() {
    override fun getNodeHeaderColor(builder: DiagramBuilder, node: DiagramNode<*>?): Color {
        val presentation = (node?.identifyingElement as? ThreadPresentation) ?: return JBColor.RED

        return presentation.color
    }
}

class ThreadInfoDiagramExtras(val project: Project,
                              val file: VirtualFile,
                              val fileContent: FileContent) : DiagramExtras<ThreadPresentation>() {
    override fun getEditNodeHandler(): EditNodeHandler<ThreadPresentation> {
        return EditNodeHandler { diagramNode, diagramPresentationModel ->
            val threadName = diagramNode.identifyingElement.name
            val offset = fileContent.run { getReadActionOffset(threadName) ?: getThreadStateOffset(threadName) ?: 0 }

            OpenFileDescriptor(project, file, offset).navigate(true)
        }
    }

    override fun createNodeComponent(node: DiagramNode<ThreadPresentation>?,
                                     builder: DiagramBuilder?,
                                     basePoint: Point?,
                                     wrapper: JPanel?): JComponent {
        return (super.createNodeComponent(node, builder, basePoint, wrapper) as DiagramNodeContainer).apply {
            synchronized(treeLock) {
                (header.components[0] as JComponent).components[0].foreground = Color.BLACK
            }
        }
    }
}

data class ThreadPresentation(val name: String,
                              val color: Color) {
    constructor(threadInfo: ThreadInfo) : this(threadInfo.threadName, threadInfo.getStateColor())
}

data class ThreadDumpDependency(val waiting: ThreadPresentation,
                                val working: ThreadPresentation) {
    constructor(waiting: ThreadInfo, working: ThreadInfo) : this(ThreadPresentation(waiting), ThreadPresentation(working))
}

class ThreadInfoNode(val threadInfo: ThreadPresentation,
                     provider: DiagramProvider<ThreadPresentation>) : DiagramNodeBase<ThreadPresentation>(provider) {
    override fun getIcon() = null
    override fun getTooltip() = null
    override fun getIdentifyingElement() = threadInfo
}

class ThreadInfoEdge(source: ThreadInfoNode,
                     target: ThreadInfoNode)
    : DiagramEdgeBase<ThreadPresentation>(source, target, ThreadInfoDiagramRelationship)

class ThreadInfoDiagramDataModel(project: Project,
                                 provider: DiagramProvider<ThreadPresentation>,
                                 dependencies: List<ThreadDumpDependency>)
    : DiagramDataModel<ThreadPresentation>(project, provider) {
    val nodesMap = HashMap<String, ThreadInfoNode>()
    val edgeList = ArrayList<ThreadInfoEdge>().apply {
        dependencies.forEach {
            val (waiting, working) = it
            val x = nodesMap.getOrPut(waiting.name, { ThreadInfoNode(waiting, provider) })
            val y = nodesMap.getOrPut(working.name, { ThreadInfoNode(working, provider) })

            add(ThreadInfoEdge(x, y))
        }
    }

    override fun dispose() = Unit
    override fun getNodes() = if (nodesMap.values.isNotEmpty()) nodesMap.values else
        listOf(ThreadInfoNode(ThreadPresentation("No problems found", Color.GREEN), provider))

    override fun getEdges() = edgeList
    override fun getNodeName(node: DiagramNode<ThreadPresentation>): String = node.identifyingElement.name
    override fun addElement(element: ThreadPresentation) = null
    override fun refreshDataModel() = Unit
    override fun getModificationTracker(): ModificationTracker = NEVER_CHANGED
}

class ThreadInfoDiagramProvider(val project: Project,
                                val file: VirtualFile,
                                val dependencies: List<ThreadDumpDependency>,
                                val fileContent: FileContent) : BaseDiagramProvider<ThreadPresentation>() {
    val PROVIDER_ID = "ThreadInfoDiagramProvider"
    override fun getID() = PROVIDER_ID
    override fun getPresentableName(): String = "My ID"
    override fun getElementManager() = ThreadInfoDiagramElementManager
    override fun getVfsResolver() = ThreadInfoVfsResolver
    override fun getColorManager() = ThreadInfoDiagramColorManager
    override fun getExtras() = ThreadInfoDiagramExtras(project, file, fileContent)
    override fun createDataModel(project: Project,
                                 element: ThreadPresentation?,
                                 file: VirtualFile?,
                                 presentationModel: DiagramPresentationModel?): DiagramDataModel<ThreadPresentation> {
        return ThreadInfoDiagramDataModel(project, this@ThreadInfoDiagramProvider, dependencies)
    }
}