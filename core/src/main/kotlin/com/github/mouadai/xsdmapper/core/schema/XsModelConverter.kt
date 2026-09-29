package com.github.mouadai.xsdmapper.core.schema

import org.apache.xerces.xs.StringList
import org.apache.xerces.xs.XSAttributeUse
import org.apache.xerces.xs.XSComplexTypeDefinition
import org.apache.xerces.xs.XSConstants
import org.apache.xerces.xs.XSElementDeclaration
import org.apache.xerces.xs.XSModel
import org.apache.xerces.xs.XSModelGroup
import org.apache.xerces.xs.XSObjectList
import org.apache.xerces.xs.XSParticle
import org.apache.xerces.xs.XSSimpleTypeDefinition
import org.apache.xerces.xs.XSTypeDefinition
import org.apache.xerces.xs.XSWildcard
import java.util.IdentityHashMap

/** Converts a Xerces [XSModel] to our own [SchemaModel]. The only place that reads Xerces schema components. */
internal class XsModelConverter(private val xsModel: XSModel, private val checkCanceled: () -> Unit) {

    private val complexTypes = LinkedHashMap<TypeId, ComplexTypeDef>()
    private val typeIds = IdentityHashMap<XSComplexTypeDefinition, TypeId>()
    private val pendingTypes = ArrayDeque<XSComplexTypeDefinition>()
    private val simpleTypes = IdentityHashMap<XSSimpleTypeDefinition, SimpleTypeDef>()
    private val globalElements = LinkedHashMap<QName, ElementDecl>()
    private var anonymousCount = 0

    fun convert(): SchemaModel {
        val declarations = xsModel.getComponents(XSConstants.ELEMENT_DECLARATION)
        val heads = (0 until declarations.length)
            .map { declarations.item(it) as XSElementDeclaration }
            .sortedBy { qName(it.namespace, it.name) }

        // Global elements first, in name order, so anonymous type numbering is stable.
        heads.forEach(::globalElement)
        while (pendingTypes.isNotEmpty()) {
            checkCanceled()
            val type = pendingTypes.removeFirst()
            val id = typeIds.getValue(type)
            complexTypes[id] = convertComplexType(id, type)
        }

        val substitutionGroups = heads.mapNotNull { head ->
            val members = xsModel.getSubstitutionGroup(head)
            if (members == null || members.length == 0) return@mapNotNull null
            val names = (0 until members.length)
                .map { members.item(it) as XSElementDeclaration }
                .map { qName(it.namespace, it.name) }
                .filter { it in globalElements }
                .sorted()
            qName(head.namespace, head.name) to names
        }.toMap()

        return SchemaModel(complexTypes, globalElements, substitutionGroups)
    }

    private fun globalElement(decl: XSElementDeclaration): ElementDecl {
        val name = qName(decl.namespace, decl.name)
        globalElements[name]?.let { return it }
        return element(decl).also { globalElements[name] = it }
    }

    private fun element(decl: XSElementDeclaration): ElementDecl {
        val (default, fixed) = valueConstraint(decl.constraintType, decl.constraintValue)
        return ElementDecl(
            name = qName(decl.namespace, decl.name),
            type = typeRef(decl.typeDefinition),
            isGlobal = decl.scope == XSConstants.SCOPE_GLOBAL,
            nillable = decl.nillable,
            isAbstract = decl.abstract,
            default = default,
            fixed = fixed,
            substitutionGroup = decl.substitutionGroupAffiliation?.let { qName(it.namespace, it.name) },
        )
    }

    private fun typeRef(type: XSTypeDefinition): TypeRef = when (type) {
        is XSComplexTypeDefinition -> ComplexTypeRef(typeId(type))
        is XSSimpleTypeDefinition -> SimpleTypeRef(simpleType(type))
        else -> error("Unexpected type definition $type")
    }

    private fun typeId(type: XSComplexTypeDefinition): TypeId = typeIds.getOrPut(type) {
        pendingTypes.addLast(type)
        if (type.anonymous) TypeId(null, anonymousCount++) else TypeId(qName(type.namespace, type.name))
    }

    private fun convertComplexType(id: TypeId, type: XSComplexTypeDefinition): ComplexTypeDef {
        val base = type.baseType
        val derivation = when (type.derivationMethod) {
            XSConstants.DERIVATION_EXTENSION -> Derivation.EXTENSION
            XSConstants.DERIVATION_RESTRICTION -> Derivation.RESTRICTION
            else -> Derivation.NONE
        }
        val baseName = base?.takeIf { it !== type && !it.anonymous }?.let { qName(it.namespace, it.name) }
        return ComplexTypeDef(
            id = id,
            contentKind = when (type.contentType) {
                XSComplexTypeDefinition.CONTENTTYPE_EMPTY -> ContentKind.EMPTY
                XSComplexTypeDefinition.CONTENTTYPE_SIMPLE -> ContentKind.SIMPLE
                XSComplexTypeDefinition.CONTENTTYPE_MIXED -> ContentKind.MIXED
                else -> ContentKind.ELEMENT_ONLY
            },
            isAbstract = type.abstract,
            baseType = baseName.takeUnless { it == ANY_TYPE },
            derivation = if (baseName == null || baseName == ANY_TYPE) Derivation.NONE else derivation,
            attributes = type.attributeUses.asList<XSAttributeUse>().map(::attribute).sortedBy { it.name },
            attributeWildcard = type.attributeWildcard?.let(::wildcard),
            // Xerces gives mixed types without child elements an empty sequence; that is no content model at all.
            particle = type.particle?.let(::particle)?.takeUnless { it is GroupParticle && it.particles.isEmpty() },
            simpleContent = type.simpleType?.let(::simpleType),
        )
    }

    private fun attribute(use: XSAttributeUse): AttributeDecl {
        val decl = use.attrDeclaration
        // A constraint on the use overrides one on the (global) declaration.
        val (default, fixed) = if (use.constraintType != XSConstants.VC_NONE) {
            valueConstraint(use.constraintType, use.constraintValue)
        } else {
            valueConstraint(decl.constraintType, decl.constraintValue)
        }
        return AttributeDecl(
            name = qName(decl.namespace, decl.name),
            type = simpleType(decl.typeDefinition),
            required = use.required,
            default = default,
            fixed = fixed,
        )
    }

    private fun particle(particle: XSParticle): Particle {
        val occurs = Occurs(particle.minOccurs, if (particle.maxOccursUnbounded) null else particle.maxOccurs)
        return when (val term = particle.term) {
            is XSElementDeclaration -> ElementParticle(
                if (term.scope == XSConstants.SCOPE_GLOBAL) globalElement(term) else element(term),
                occurs,
            )
            is XSModelGroup -> GroupParticle(
                compositor = when (term.compositor) {
                    XSModelGroup.COMPOSITOR_CHOICE -> Compositor.CHOICE
                    XSModelGroup.COMPOSITOR_ALL -> Compositor.ALL
                    else -> Compositor.SEQUENCE
                },
                particles = term.particles.asList<XSParticle>().map(::particle),
                occurs = occurs,
            )
            is XSWildcard -> WildcardParticle(wildcard(term), occurs)
            else -> error("Unexpected particle term $term")
        }
    }

    private fun wildcard(wildcard: XSWildcard): Wildcard {
        val namespaces = wildcard.nsConstraintList.asStrings()
        return Wildcard(
            namespaces = when (wildcard.constraintType) {
                XSWildcard.NSCONSTRAINT_ANY -> NamespaceConstraint.Any
                XSWildcard.NSCONSTRAINT_NOT -> NamespaceConstraint.Not(namespaces)
                else -> NamespaceConstraint.OneOf(namespaces)
            },
            processContents = when (wildcard.processContents) {
                XSWildcard.PC_LAX -> ProcessContents.LAX
                XSWildcard.PC_SKIP -> ProcessContents.SKIP
                else -> ProcessContents.STRICT
            },
        )
    }

    private fun simpleType(type: XSSimpleTypeDefinition): SimpleTypeDef = simpleTypes[type] ?: SimpleTypeDef(
        name = if (type.anonymous) null else qName(type.namespace, type.name),
        variety = when (type.variety) {
            XSSimpleTypeDefinition.VARIETY_LIST -> Variety.LIST
            XSSimpleTypeDefinition.VARIETY_UNION -> Variety.UNION
            else -> Variety.ATOMIC
        },
        builtinBase = builtinBase(type),
        facets = facets(type),
        itemType = type.itemType?.let(::simpleType),
        memberTypes = type.memberTypes.asList<XSSimpleTypeDefinition>().map(::simpleType),
    ).also { simpleTypes[type] = it }

    private fun builtinBase(type: XSSimpleTypeDefinition): String {
        var current: XSTypeDefinition? = type
        while (current != null) {
            if (current.namespace == XSD_NAMESPACE && !current.anonymous) return current.name
            current = current.baseType.takeIf { it !== current }
        }
        return "anySimpleType"
    }

    private fun facets(type: XSSimpleTypeDefinition): Facets {
        fun value(facet: Short): String? =
            if (type.isDefinedFacet(facet)) type.getLexicalFacetValue(facet) else null

        fun int(facet: Short): Int? = value(facet)?.toIntOrNull()

        return Facets(
            enumerations = if (type.isDefinedFacet(XSSimpleTypeDefinition.FACET_ENUMERATION)) {
                type.lexicalEnumeration.asStrings().filterNotNull()
            } else {
                emptyList()
            },
            patterns = if (type.isDefinedFacet(XSSimpleTypeDefinition.FACET_PATTERN)) {
                type.lexicalPattern.asStrings().filterNotNull()
            } else {
                emptyList()
            },
            length = int(XSSimpleTypeDefinition.FACET_LENGTH),
            minLength = int(XSSimpleTypeDefinition.FACET_MINLENGTH),
            maxLength = int(XSSimpleTypeDefinition.FACET_MAXLENGTH),
            totalDigits = int(XSSimpleTypeDefinition.FACET_TOTALDIGITS),
            fractionDigits = int(XSSimpleTypeDefinition.FACET_FRACTIONDIGITS),
            minInclusive = value(XSSimpleTypeDefinition.FACET_MININCLUSIVE),
            maxInclusive = value(XSSimpleTypeDefinition.FACET_MAXINCLUSIVE),
            minExclusive = value(XSSimpleTypeDefinition.FACET_MINEXCLUSIVE),
            maxExclusive = value(XSSimpleTypeDefinition.FACET_MAXEXCLUSIVE),
        )
    }

    private fun valueConstraint(type: Short, value: String?): Pair<String?, String?> = when (type) {
        XSConstants.VC_DEFAULT -> value to null
        XSConstants.VC_FIXED -> null to value
        else -> null to null
    }

    private fun qName(namespace: String?, localName: String): QName =
        QName(namespace?.takeIf { it.isNotEmpty() }, localName)

    companion object {
        const val XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema"
        val ANY_TYPE = QName(XSD_NAMESPACE, "anyType")
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> XSObjectList?.asList(): List<T> =
    if (this == null) emptyList() else (0 until length).map { item(it) as T }

private fun StringList?.asStrings(): List<String?> =
    if (this == null) emptyList() else (0 until length).map { item(it) }
