package com.github.mouadai.xsdmapper.core.schema

import java.net.URI

/**
 * An immutable, navigable view of a loaded schema: one element tree per root candidate.
 *
 * Children are created on first access and then cached, so real-world schemas whose fully expanded tree
 * would be enormous (UBL reuses the same aggregates everywhere) stay cheap. Recursion is cut at the first
 * element whose complex type already appears on the path from the root, where a [RecursionNode] is placed.
 */
class SchemaTree(
    val location: URI,
    /** The target namespace of the entry schema document. */
    val targetNamespace: String?,
    val model: SchemaModel,
    /** Global elements that can be document roots: those of the entry namespace, unreferenced ones first. */
    val rootCandidates: List<QName>,
    val diagnostics: List<SchemaDiagnostic>,
) {
    val hasErrors: Boolean get() = diagnostics.any { it.severity != SchemaDiagnostic.Severity.WARNING }

    val roots: List<ElementNode> by lazy { rootCandidates.map(::root) }

    /** The element tree rooted at global element [name]. */
    fun root(name: QName): ElementNode = ElementNode(model, model.globalElement(name), Occurs.ONCE, null)
}

data class SchemaDiagnostic(
    val severity: Severity,
    val message: String,
    val systemId: String? = null,
    val line: Int = -1,
    val column: Int = -1,
) {
    enum class Severity { WARNING, ERROR, FATAL }

    override fun toString(): String = buildString {
        append(severity).append(": ").append(message)
        if (systemId != null) {
            append(" (").append(systemId.substringAfterLast('/'))
            if (line >= 0) append(':').append(line)
            append(')')
        }
    }
}

/** A node of [SchemaTree]. Every node is immutable; [children] are computed once, on first access. */
sealed class SchemaNode {
    abstract val children: List<SchemaNode>
}

/** The chain of complex types from the root down to a node, used to detect recursion. */
internal class TypePath(val id: TypeId, val parent: TypePath?) {
    /** Number of element levels up to the nearest ancestor of type [target], or -1 if there is none. */
    fun distanceTo(target: TypeId): Int {
        var node: TypePath? = this
        var distance = 1
        while (node != null) {
            if (node.id == target) return distance
            node = node.parent
            distance++
        }
        return -1
    }
}

class ElementNode internal constructor(
    private val model: SchemaModel,
    val decl: ElementDecl,
    val occurs: Occurs,
    ancestors: TypePath?,
) : SchemaNode() {
    private val path: TypePath? = (decl.type as? ComplexTypeRef)?.let { TypePath(it.id, ancestors) } ?: ancestors

    val name: QName get() = decl.name

    /** The name of the element's type, or null for an anonymous type. */
    val typeName: QName? get() = decl.type.name

    val complexType: ComplexTypeDef? = (decl.type as? ComplexTypeRef)?.let { model.complexType(it.id) }

    /** The value type of a simple-typed element or of a complex type with simple content. */
    val simpleType: SimpleTypeDef? = when (val type = decl.type) {
        is SimpleTypeRef -> type.def
        is ComplexTypeRef -> complexType?.simpleContent
    }

    val contentKind: ContentKind = complexType?.contentKind ?: ContentKind.SIMPLE

    val attributes: List<SchemaNode> by lazy {
        val type = complexType ?: return@lazy emptyList()
        type.attributes.map(::AttributeNode) + listOfNotNull(type.attributeWildcard?.let(::AnyAttributeNode))
    }

    /** The content model: a [CompositorNode] (or [AnyNode]), or null for empty and simple content. */
    val content: SchemaNode? by lazy { complexType?.particle?.let { particleNode(model, it, path) } }

    override val children: List<SchemaNode> by lazy { attributes + listOfNotNull(content) }

    override fun toString(): String = "element $name $occurs"
}

class AttributeNode internal constructor(val decl: AttributeDecl) : SchemaNode() {
    val name: QName get() = decl.name
    override val children: List<SchemaNode> get() = emptyList()
    override fun toString(): String = "attribute $name"
}

/** `xs:anyAttribute`. */
class AnyAttributeNode internal constructor(val wildcard: Wildcard) : SchemaNode() {
    override val children: List<SchemaNode> get() = emptyList()
    override fun toString(): String = "anyAttribute"
}

/** An `xs:sequence`, `xs:choice` or `xs:all`, kept as an explicit node so choice branches stay visible. */
class CompositorNode internal constructor(
    private val model: SchemaModel,
    val compositor: Compositor,
    val occurs: Occurs,
    private val particles: List<Particle>,
    private val path: TypePath?,
) : SchemaNode() {
    override val children: List<SchemaNode> by lazy { particles.map { particleNode(model, it, path) } }
    override fun toString(): String = "${compositor.xsdName} $occurs"
}

/** `xs:any`. */
class AnyNode internal constructor(val wildcard: Wildcard, val occurs: Occurs) : SchemaNode() {
    override val children: List<SchemaNode> get() = emptyList()
    override fun toString(): String = "any $occurs"
}

/**
 * A reference to the head of a substitution group. [members] lists every element allowed in that position:
 * the head itself unless it is abstract, then its substitutes.
 */
class SubstitutionGroupNode internal constructor(
    private val model: SchemaModel,
    val head: ElementDecl,
    val occurs: Occurs,
    private val path: TypePath?,
) : SchemaNode() {
    val members: List<SchemaNode> by lazy {
        val candidates = listOf(head) + model.substitutionGroups[head.name].orEmpty().map(model::globalElement)
        candidates.filterNot { it.isAbstract }.map { elementNode(model, it, Occurs.ONCE, path) }
    }

    override val children: List<SchemaNode> get() = members
    override fun toString(): String = "substitutionGroup ${head.name} $occurs"
}

/**
 * An element whose complex type is already being expanded by an ancestor. It is not expanded again;
 * [distance] is the number of levels up to that ancestor (1 = the parent element).
 */
class RecursionNode internal constructor(
    val decl: ElementDecl,
    val occurs: Occurs,
    val targetType: TypeId,
    val distance: Int,
) : SchemaNode() {
    val name: QName get() = decl.name
    override val children: List<SchemaNode> get() = emptyList()
    override fun toString(): String = "recursion $name $occurs"
}

internal fun particleNode(model: SchemaModel, particle: Particle, path: TypePath?): SchemaNode = when (particle) {
    is GroupParticle -> CompositorNode(model, particle.compositor, particle.occurs, particle.particles, path)
    is WildcardParticle -> AnyNode(particle.wildcard, particle.occurs)
    is ElementParticle -> {
        val decl = particle.element
        if (decl.isGlobal && model.substitutionGroups[decl.name].orEmpty().isNotEmpty()) {
            SubstitutionGroupNode(model, decl, particle.occurs, path)
        } else {
            elementNode(model, decl, particle.occurs, path)
        }
    }
}

internal fun elementNode(model: SchemaModel, decl: ElementDecl, occurs: Occurs, path: TypePath?): SchemaNode {
    val type = decl.type
    if (type is ComplexTypeRef && path != null) {
        val distance = path.distanceTo(type.id)
        if (distance > 0) return RecursionNode(decl, occurs, type.id, distance)
    }
    return ElementNode(model, decl, occurs, path)
}
