package com.github.mouadai.xsdmapper.plugin

import com.github.mouadai.xsdmapper.core.schema.SchemaDiagnostic
import com.github.mouadai.xsdmapper.core.schema.SchemaNode
import com.github.mouadai.xsdmapper.core.schema.SchemaTree
import javax.swing.event.TreeModelListener
import javax.swing.tree.TreeModel
import javax.swing.tree.TreePath

/**
 * Adapts an immutable [SchemaTree] to Swing. Tree nodes are the core [SchemaNode]s themselves, plus a [Root]
 * and a [Diagnostics] group. The model never changes; a new schema gets a new model.
 */
class SchemaTreeModel(private val root: Any) : TreeModel {

    class Root(val fileName: String, val schema: SchemaTree) {
        val children: List<Any> =
            listOfNotNull(schema.diagnostics.takeIf { it.isNotEmpty() }?.let(::Diagnostics)) + schema.roots
    }

    class Diagnostics(val items: List<SchemaDiagnostic>)

    override fun getRoot(): Any = root

    override fun getChild(parent: Any, index: Int): Any = children(parent)[index]

    override fun getChildCount(parent: Any): Int = children(parent).size

    override fun isLeaf(node: Any): Boolean = children(node).isEmpty()

    override fun getIndexOfChild(parent: Any?, child: Any?): Int =
        if (parent == null || child == null) -1 else children(parent).indexOf(child)

    override fun valueForPathChanged(path: TreePath?, newValue: Any?) = Unit

    override fun addTreeModelListener(listener: TreeModelListener?) = Unit

    override fun removeTreeModelListener(listener: TreeModelListener?) = Unit

    private fun children(node: Any): List<Any> = when (node) {
        is Root -> node.children
        is Diagnostics -> node.items
        is SchemaNode -> node.children
        else -> emptyList()
    }

    companion object {
        val EMPTY = SchemaTreeModel("No schema loaded")
    }
}
