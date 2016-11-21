package org.compscicenter.typingFreezeAnalyzer

import com.intellij.diagram.*
import com.intellij.diagram.extras.DiagramExtras
import com.intellij.diagram.extras.EditNodeHandler
import com.intellij.diagram.presentation.DiagramState
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Shape
import java.lang.management.ThreadInfo
import java.util.*

object DefaultDiagramRelationshipInfo : DiagramRelationshipInfoAdapter(null) {
    override fun getStartArrow(): Shape = DiagramRelationshipInfo.DIAMOND
}

object DefaultVfsResolver : DiagramVfsResolver<ThreadInfo> {
    override fun getQualifiedName(element: ThreadInfo): String = element.threadName
    override fun resolveElementByFQN(fqn: String?, project: Project?) = null
}

object DefaultDiagramElementManager : AbstractDiagramElementManager<ThreadInfo>() {
    override fun getNodeTooltip(element: ThreadInfo) = null
    override fun findInDataContext(context: DataContext?) = null
    override fun isAcceptableAsNode(element: Any?) = element != null
    override fun getElementTitle(element: ThreadInfo): String = if (element.isAWTThread()) "AWT" else "${element.threadId}"
    override fun getItemName(element: Any?, presentation: DiagramState?) = null
}

object DefaultDiagramColorManager : DiagramColorManagerBase() {
    override fun getNodeHeaderColor(builder: DiagramBuilder?, node: DiagramNode<*>?) =
            (node?.identifyingElement as ThreadInfo).getStateColor()
//    override fun getNodeForegroundColor(selected: Boolean): Color? = JBColor.WHITE
//    override fun getNodeBackground(project: Project?, nodeElement: Any?, selected: Boolean): Color = JBColor.WHITE
}

class DefaultDiagramExtras(val project: Project,
                           val file: VirtualFile,
                           val fileContent: FileContent) : DiagramExtras<ThreadInfo>() {
    override fun getEditNodeHandler(): EditNodeHandler<ThreadInfo> {
        return EditNodeHandler { diagramNode, diagramPresentationModel ->
            val thread = diagramNode.identifyingElement
            val offset = if (thread.isAWTThread()) 1 else fileContent.getReadActionOffset(thread.threadId)
            val editorIsOpen = FileEditorManager.getInstance(project).getSelectedEditor(file) != null

            OpenFileDescriptor(project, file, offset).navigate(true)
            if (!editorIsOpen) enrichFile(project, fileContent)
        }
    }
}

class ThreadInfoNode(val threadInfo: ThreadInfo,
                     provider: DiagramProvider<ThreadInfo>) : DiagramNodeBase<ThreadInfo>(provider) {
    override fun getIcon() = null
    override fun getTooltip() = null
    override fun getIdentifyingElement() = threadInfo
}

class StringEdge(source: ThreadInfoNode,
                 target: ThreadInfoNode) : DiagramEdgeBase<ThreadInfo>(source, target, DefaultDiagramRelationshipInfo)

class StringDiagramProvider(val project: Project,
                            val file: VirtualFile,
                            val dependencies: List<Pair<ThreadInfo, ThreadInfo>>,
                            val fileContent: FileContent) : BaseDiagramProvider<ThreadInfo>() {
    val PROVIDER_ID = "StringDiagramProvider"
    override fun getID() = PROVIDER_ID
    override fun getPresentableName(): String = "My ID"
    override fun getElementManager() = DefaultDiagramElementManager
    override fun getVfsResolver() = DefaultVfsResolver
    override fun getColorManager() = DefaultDiagramColorManager
    override fun getExtras() = DefaultDiagramExtras(project, file, fileContent)
//    override fun createPresentationModel(project: Project?, graph: Graph2D?): DiagramPresentationModel? {
//        return DiagramPresentationModelImpl(graph, project, this)
//    }


    override fun createDataModel(project: Project,
                                 element: ThreadInfo?,
                                 file: VirtualFile?,
                                 presentationModel: DiagramPresentationModel?): DiagramDataModel<ThreadInfo> {
        return object : DiagramDataModel<ThreadInfo>(project, this) {
            val nodesMap = HashMap<String, ThreadInfoNode>()
            val edgeList = ArrayList<StringEdge>().apply {
                dependencies.forEach {
                    val x = nodesMap.getOrPut(it.first.threadName, { ThreadInfoNode(it.first, this@StringDiagramProvider) })
                    val y = nodesMap.getOrPut(it.second.threadName, { ThreadInfoNode(it.second, this@StringDiagramProvider) })

                    add(StringEdge(x, y))
                }
            }

            override fun dispose() = Unit
            override fun getNodes() = nodesMap.values
            override fun getEdges() = edgeList
            override fun getNodeName(node: DiagramNode<ThreadInfo>) = node.identifyingElement.threadName
            override fun addElement(element: ThreadInfo) = null
            override fun refreshDataModel() = Unit
            override fun getModificationTracker() = NEVER_CHANGED
        }
    }
}