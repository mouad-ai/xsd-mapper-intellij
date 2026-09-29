package com.github.mouadai.xsdmapper.core.schema

/**
 * Renders a [SchemaTree] as indented text: used for snapshot tests and for debugging.
 *
 * Namespaces are written as prefixes `ns0`, `ns1`, ... declared in a header, in order of first use, so output stays
 * readable for schemas with long namespace URIs.
 */
class SchemaTreePrinter(
    /** Elements deeper than this are printed without children, marked with `…`. */
    private val maxDepth: Int = Int.MAX_VALUE,
) {
    fun print(tree: SchemaTree): String {
        val body = StringBuilder()
        val prefixes = LinkedHashMap<String, String>()
        val printer = Printer(body, prefixes)
        tree.roots.forEach { printer.node(it, 0, 0) }

        return buildString {
            append("schema ").append(tree.location.path.substringAfterLast('/')).append('\n')
            append("targetNamespace ").append(tree.targetNamespace ?: "(none)").append('\n')
            prefixes.forEach { (ns, prefix) -> append("xmlns:").append(prefix).append(' ').append(ns).append('\n') }
            append("roots ").append(tree.rootCandidates.joinToString(" ") { printer.name(it) }).append('\n')
            append('\n')
            append(body)
        }
    }

    private inner class Printer(private val out: StringBuilder, private val prefixes: MutableMap<String, String>) {

        fun name(name: QName): String {
            val ns = name.namespace ?: return name.localName
            if (ns == XsModelConverter.XSD_NAMESPACE) return "xs:${name.localName}"
            val prefix = prefixes.getOrPut(ns) { "ns${prefixes.size}" }
            return "$prefix:${name.localName}"
        }

        fun node(node: SchemaNode, indent: Int, elementDepth: Int) {
            out.append("  ".repeat(indent))
            when (node) {
                is ElementNode -> {
                    out.append("element ").append(name(node.name)).append(' ').append(node.occurs)
                    out.append(" : ").append(typeLabel(node))
                    flags(node.decl)
                    if (node.contentKind == ContentKind.MIXED) out.append(" mixed")
                    if (node.decl.substitutionGroup != null) {
                        out.append(" substitutes=").append(name(node.decl.substitutionGroup))
                    }
                    node.simpleType?.let { simpleType(it) }
                    if (elementDepth >= maxDepth && node.children.isNotEmpty()) {
                        out.append(" …\n")
                        return
                    }
                    out.append('\n')
                    node.children.forEach { node(it, indent + 1, elementDepth + 1) }
                    return
                }
                is AttributeNode -> {
                    val decl = node.decl
                    out.append("attribute ").append(name(decl.name))
                    out.append(if (decl.required) " required" else " optional")
                    out.append(" : ").append(decl.type.name?.let(::name) ?: "(anonymous)")
                    decl.default?.let { out.append(" default=").append(quote(it)) }
                    decl.fixed?.let { out.append(" fixed=").append(quote(it)) }
                    simpleType(decl.type)
                }
                is AnyAttributeNode -> out.append("anyAttribute ").append(wildcard(node.wildcard))
                is CompositorNode -> {
                    out.append(node.compositor.xsdName).append(' ').append(node.occurs).append('\n')
                    node.children.forEach { node(it, indent + 1, elementDepth) }
                    return
                }
                is AnyNode -> out.append("any ").append(node.occurs).append(' ').append(wildcard(node.wildcard))
                is SubstitutionGroupNode -> {
                    out.append("substitutionGroup ").append(name(node.head.name)).append(' ').append(node.occurs)
                    out.append('\n')
                    node.children.forEach { node(it, indent + 1, elementDepth) }
                    return
                }
                is RecursionNode -> {
                    out.append("recursion ").append(name(node.name)).append(' ').append(node.occurs)
                    out.append(" : ").append(node.targetType.name?.let(::name) ?: "(anonymous)")
                    out.append(" -> ancestor ").append(node.distance)
                    flags(node.decl)
                }
            }
            out.append('\n')
        }

        private fun typeLabel(node: ElementNode): String = node.typeName?.let(::name) ?: "(anonymous)"

        private fun flags(decl: ElementDecl) {
            if (decl.nillable) out.append(" nillable")
            if (decl.isAbstract) out.append(" abstract")
            decl.default?.let { out.append(" default=").append(quote(it)) }
            decl.fixed?.let { out.append(" fixed=").append(quote(it)) }
        }

        private fun simpleType(type: SimpleTypeDef) {
            out.append(" <").append(describe(type)).append('>')
        }

        private fun describe(type: SimpleTypeDef): String = buildString {
            when (type.variety) {
                Variety.ATOMIC -> append(type.builtinBase)
                Variety.LIST -> append("list of ").append(type.itemType?.let { it.name?.let(::name) ?: describe(it) })
                Variety.UNION -> append("union of ").append(
                    type.memberTypes.joinToString(" | ") { member -> member.name?.let(::name) ?: describe(member) },
                )
            }
            val facets = facets(type.facets)
            if (facets.isNotEmpty()) append(' ').append(facets.joinToString(" "))
        }

        private fun facets(facets: Facets): List<String> = buildList {
            if (facets.enumerations.isNotEmpty()) add("enum=" + facets.enumerations.joinToString("|", "[", "]"))
            facets.patterns.forEach { add("pattern=" + quote(it)) }
            facets.length?.let { add("length=$it") }
            facets.minLength?.let { add("minLength=$it") }
            facets.maxLength?.let { add("maxLength=$it") }
            facets.totalDigits?.let { add("totalDigits=$it") }
            facets.fractionDigits?.let { add("fractionDigits=$it") }
            facets.minInclusive?.let { add("minInclusive=$it") }
            facets.maxInclusive?.let { add("maxInclusive=$it") }
            facets.minExclusive?.let { add("minExclusive=$it") }
            facets.maxExclusive?.let { add("maxExclusive=$it") }
        }

        private fun wildcard(wildcard: Wildcard): String {
            fun ns(namespace: String?) = namespace ?: "##local"
            val namespaces = when (val constraint = wildcard.namespaces) {
                NamespaceConstraint.Any -> "##any"
                is NamespaceConstraint.Not -> constraint.namespaces.joinToString(" ", "not(", ")") { ns(it) }
                is NamespaceConstraint.OneOf -> constraint.namespaces.joinToString(" ", "(", ")") { ns(it) }
            }
            return "$namespaces ${wildcard.processContents.xsdName}"
        }

        private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
