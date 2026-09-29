package com.github.mouadai.xsdmapper.core.schema

/**
 * The definition layer of a loaded schema: every global element and every complex type, each converted once.
 *
 * Types reference each other by [TypeId], so this model is finite even for recursive schemas.
 * [SchemaTree] builds the navigable element tree on top of it.
 */
class SchemaModel(
    val complexTypes: Map<TypeId, ComplexTypeDef>,
    val globalElements: Map<QName, ElementDecl>,
    /** Substitution group head -> every element that may substitute for it (transitively), sorted by name. */
    val substitutionGroups: Map<QName, List<QName>>,
) {
    fun complexType(id: TypeId): ComplexTypeDef =
        complexTypes[id] ?: throw IllegalArgumentException("Unknown complex type $id")

    fun globalElement(name: QName): ElementDecl =
        globalElements[name] ?: throw IllegalArgumentException("Unknown global element $name")

    companion object {
        val EMPTY = SchemaModel(emptyMap(), emptyMap(), emptyMap())
    }
}

/** Qualified XML name. [namespace] is null for names in no namespace. */
data class QName(val namespace: String?, val localName: String) : Comparable<QName> {
    override fun compareTo(other: QName): Int =
        compareValuesBy(this, other, { it.namespace.orEmpty() }, { it.localName })

    override fun toString(): String = if (namespace.isNullOrEmpty()) localName else "{$namespace}$localName"
}

/** minOccurs/maxOccurs. [max] is null for `unbounded`. */
data class Occurs(val min: Int, val max: Int?) {
    val isUnbounded: Boolean get() = max == null
    val isOptional: Boolean get() = min == 0
    val isRepeating: Boolean get() = max == null || max > 1

    override fun toString(): String = "[$min..${max ?: "*"}]"

    companion object {
        val ONCE = Occurs(1, 1)
    }
}

/** Identity of a complex type. Named types are identified by [name], anonymous ones by [anonymousIndex]. */
data class TypeId(val name: QName?, val anonymousIndex: Int = -1) {
    val isAnonymous: Boolean get() = name == null

    override fun toString(): String = name?.toString() ?: "#anonymous$anonymousIndex"
}

enum class ContentKind { EMPTY, SIMPLE, ELEMENT_ONLY, MIXED }

enum class Derivation { NONE, EXTENSION, RESTRICTION }

enum class Compositor(val xsdName: String) { SEQUENCE("sequence"), CHOICE("choice"), ALL("all") }

enum class Variety { ATOMIC, LIST, UNION }

enum class ProcessContents(val xsdName: String) { STRICT("strict"), LAX("lax"), SKIP("skip") }

/**
 * The effective constraining facets of a simple type, inherited ones included.
 *
 * [patterns] holds one regular expression per derivation step that declared patterns; a value must match all of them.
 */
data class Facets(
    val enumerations: List<String> = emptyList(),
    val patterns: List<String> = emptyList(),
    val length: Int? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val totalDigits: Int? = null,
    val fractionDigits: Int? = null,
    val minInclusive: String? = null,
    val maxInclusive: String? = null,
    val minExclusive: String? = null,
    val maxExclusive: String? = null,
) {
    val isEmpty: Boolean get() = this == NONE

    companion object {
        val NONE = Facets()
    }
}

/** A simple type. [builtinBase] is the local name of the nearest built-in XSD ancestor, e.g. `decimal`. */
data class SimpleTypeDef(
    val name: QName?,
    val variety: Variety,
    val builtinBase: String,
    val facets: Facets,
    val itemType: SimpleTypeDef? = null,
    val memberTypes: List<SimpleTypeDef> = emptyList(),
) {
    val isAnonymous: Boolean get() = name == null
}

sealed interface TypeRef {
    /** The type's name, or null for an anonymous type. */
    val name: QName?
}

data class ComplexTypeRef(val id: TypeId) : TypeRef {
    override val name: QName? get() = id.name
}

data class SimpleTypeRef(val def: SimpleTypeDef) : TypeRef {
    override val name: QName? get() = def.name
}

data class ElementDecl(
    val name: QName,
    val type: TypeRef,
    val isGlobal: Boolean,
    val nillable: Boolean = false,
    val isAbstract: Boolean = false,
    val default: String? = null,
    val fixed: String? = null,
    /** The head of the substitution group this element belongs to, if any. */
    val substitutionGroup: QName? = null,
)

data class AttributeDecl(
    val name: QName,
    val type: SimpleTypeDef,
    val required: Boolean,
    val default: String? = null,
    val fixed: String? = null,
)

sealed interface NamespaceConstraint {
    /** `##any`. */
    data object Any : NamespaceConstraint

    /** `##other` and similar: any namespace except these. A null entry is the absent namespace. */
    data class Not(val namespaces: List<String?>) : NamespaceConstraint

    /** An explicit list of namespaces. A null entry is the absent namespace (`##local`). */
    data class OneOf(val namespaces: List<String?>) : NamespaceConstraint
}

data class Wildcard(val namespaces: NamespaceConstraint, val processContents: ProcessContents)

sealed interface Particle {
    val occurs: Occurs
}

data class ElementParticle(val element: ElementDecl, override val occurs: Occurs) : Particle

data class GroupParticle(
    val compositor: Compositor,
    val particles: List<Particle>,
    override val occurs: Occurs,
) : Particle

data class WildcardParticle(val wildcard: Wildcard, override val occurs: Occurs) : Particle

data class ComplexTypeDef(
    val id: TypeId,
    val contentKind: ContentKind,
    val isAbstract: Boolean,
    /** The named base type, or null when the base is `xs:anyType` by default. */
    val baseType: QName?,
    val derivation: Derivation,
    val attributes: List<AttributeDecl>,
    val attributeWildcard: Wildcard?,
    /** The content model, or null for empty and simple content. */
    val particle: Particle?,
    /** The value type for simple content. */
    val simpleContent: SimpleTypeDef?,
) {
    val name: QName? get() = id.name
}
