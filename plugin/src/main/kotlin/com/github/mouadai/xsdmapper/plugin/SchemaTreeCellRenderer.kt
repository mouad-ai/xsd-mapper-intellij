package com.github.mouadai.xsdmapper.plugin

import com.github.mouadai.xsdmapper.core.schema.AnyAttributeNode
import com.github.mouadai.xsdmapper.core.schema.AnyNode
import com.github.mouadai.xsdmapper.core.schema.AttributeNode
import com.github.mouadai.xsdmapper.core.schema.CompositorNode
import com.github.mouadai.xsdmapper.core.schema.ContentKind
import com.github.mouadai.xsdmapper.core.schema.ElementDecl
import com.github.mouadai.xsdmapper.core.schema.ElementNode
import com.github.mouadai.xsdmapper.core.schema.NamespaceConstraint
import com.github.mouadai.xsdmapper.core.schema.Occurs
import com.github.mouadai.xsdmapper.core.schema.QName
import com.github.mouadai.xsdmapper.core.schema.RecursionNode
import com.github.mouadai.xsdmapper.core.schema.SchemaDiagnostic
import com.github.mouadai.xsdmapper.core.schema.SimpleTypeDef
import com.github.mouadai.xsdmapper.core.schema.SubstitutionGroupNode
import com.github.mouadai.xsdmapper.core.schema.Variety
import com.github.mouadai.xsdmapper.core.schema.Wildcard
import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTree

class SchemaTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        toolTipText = null
        when (value) {
            is SchemaTreeModel.Root -> {
                icon = AllIcons.FileTypes.Xml
                append(value.fileName)
                value.schema.targetNamespace?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
            is SchemaTreeModel.Diagnostics -> {
                val errors = value.items.count { it.severity != SchemaDiagnostic.Severity.WARNING }
                icon = if (errors > 0) AllIcons.General.Error else AllIcons.General.Warning
                append("Problems (${value.items.size})")
            }
            is SchemaDiagnostic -> {
                icon = if (value.severity == SchemaDiagnostic.Severity.WARNING) AllIcons.General.Warning else AllIcons.General.Error
                append(value.toString())
            }
            is ElementNode -> {
                icon = AllIcons.Nodes.Tag
                append(value.name.localName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                occurs(value.occurs)
                type(value.typeName, value.simpleType)
                flags(value.decl)
                if (value.contentKind == ContentKind.MIXED) append(" mixed", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                toolTipText = namespaceTip(value.name)
            }
            is AttributeNode -> {
                icon = AllIcons.Nodes.Property
                append("@" + value.name.localName)
                append(if (value.decl.required) " required" else " optional", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                type(value.decl.type.name, value.decl.type)
                value.decl.default?.let { append(" default=\"$it\"", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                value.decl.fixed?.let { append(" fixed=\"$it\"", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                toolTipText = namespaceTip(value.name)
            }
            is AnyAttributeNode -> {
                icon = AllIcons.Nodes.Property
                append("@* ", SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES)
                append(wildcard(value.wildcard), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is CompositorNode -> {
                icon = null
                append(value.compositor.xsdName, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                occurs(value.occurs)
            }
            is AnyNode -> {
                icon = AllIcons.Nodes.Tag
                append("any", SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES)
                occurs(value.occurs)
                append("  " + wildcard(value.wildcard), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is SubstitutionGroupNode -> {
                icon = AllIcons.Nodes.AbstractClass
                append(value.head.name.localName, SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES)
                occurs(value.occurs)
                append("  substitution group, ${value.members.size} members", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = namespaceTip(value.head.name)
            }
            is RecursionNode -> {
                icon = AllIcons.Gutter.RecursiveMethod
                append(value.name.localName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                occurs(value.occurs)
                type(value.targetType.name, null)
                val levels = if (value.distance == 1) "parent" else "${value.distance} levels up"
                append("  recursive, see $levels", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                toolTipText = namespaceTip(value.name)
            }
            else -> append(value?.toString().orEmpty(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private fun occurs(occurs: Occurs) {
        if (occurs != Occurs.ONCE) append(" $occurs", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    private fun type(name: QName?, simpleType: SimpleTypeDef?) {
        val label = name?.localName ?: simpleType?.let(::describe) ?: "anonymous"
        append(" : $label", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    private fun describe(type: SimpleTypeDef): String = when (type.variety) {
        Variety.ATOMIC -> type.builtinBase
        Variety.LIST -> "list of " + (type.itemType?.let { it.name?.localName ?: describe(it) } ?: "?")
        Variety.UNION -> type.memberTypes.joinToString(" | ") { it.name?.localName ?: describe(it) }
    }

    private fun flags(decl: ElementDecl) {
        if (decl.nillable) append(" nillable", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        decl.default?.let { append(" default=\"$it\"", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        decl.fixed?.let { append(" fixed=\"$it\"", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
    }

    private fun wildcard(wildcard: Wildcard): String {
        fun ns(namespace: String?) = namespace ?: "##local"
        val namespaces = when (val constraint = wildcard.namespaces) {
            NamespaceConstraint.Any -> "##any"
            is NamespaceConstraint.Not -> "not " + constraint.namespaces.joinToString(" ") { ns(it) }
            is NamespaceConstraint.OneOf -> constraint.namespaces.joinToString(" ") { ns(it) }
        }
        return "$namespaces (${wildcard.processContents.xsdName})"
    }

    private fun namespaceTip(name: QName): String? = name.namespace
}
