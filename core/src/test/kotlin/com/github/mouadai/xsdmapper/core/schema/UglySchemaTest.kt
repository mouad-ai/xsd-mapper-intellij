package com.github.mouadai.xsdmapper.core.schema

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Targeted checks for each hand-written schema in testdata/ugly. */
class UglySchemaTest {

    @Nested
    inner class IncludeChain {
        private val tree = Corpus.load("ugly/include-chain/main.xsd")
        private val ns = "urn:ugly:include"

        @Test
        fun `resolves every level of the chain relative to the including document`() {
            assertThat(tree.hasErrors).isFalse()
            // Declared five includes deep, in shared/units/units.xsd.
            val currency = tree.element("Total").attributes.filterIsInstance<AttributeNode>().single()
            assertThat(currency.decl.type.name).isEqualTo(QName(ns, "CurrencyCodeType"))
            assertThat(currency.decl.type.facets.enumerations).containsExactly("EUR", "USD", "MAD")
        }

        @Test
        fun `chameleon include takes the including namespace`() {
            val id = tree.element("Id")
            assertThat(id.typeName).isEqualTo(QName(ns, "IdentifierType"))
            assertThat(id.simpleType!!.facets.patterns).containsExactly("[A-Z]{2}-[0-9]{6}")
        }

        @Test
        fun `redefine extends the original type`() {
            val shipTo = tree.element("ShipTo")
            assertThat(shipTo.complexType!!.derivation).isEqualTo(Derivation.EXTENSION)
            assertThat(localNames(shipTo)).containsExactly("Street", "City", "Country")
        }

        @Test
        fun `uses an unsaved buffer for the entry document and still resolves includes from disk`() {
            val edited = SchemaLoader().load(
                Corpus.path("ugly/include-chain/main.xsd"),
                content = """
                    <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns="$ns" targetNamespace="$ns"
                               elementFormDefault="qualified">
                        <xs:include schemaLocation="level1/level2/lines.xsd"/>
                        <xs:element name="Edited" type="LineType"/>
                    </xs:schema>
                """.trimIndent(),
            )

            assertThat(edited.hasErrors).isFalse()
            assertThat(edited.rootCandidates).containsExactly(QName(ns, "Edited"))
            assertThat(localNames(edited.roots.single())).containsExactly("Product", "Quantity", "Price")
        }
    }

    @Nested
    inner class CrossNamespaceImports {
        private val tree = Corpus.load("ugly/cross-namespace-imports/main.xsd")

        @Test
        fun `loads both documents of a namespace imported from different schemas`() {
            assertThat(tree.hasErrors).isFalse()
            val country = tree.elements("Country").first()
            assertThat(country.typeName).isEqualTo(QName("urn:ugly:common", "CountryCodeType"))
            assertThat(tree.element("TaxId").name.namespace).isEqualTo("urn:ugly:common")
        }

        @Test
        fun `keeps each namespace's element form`() {
            assertThat(tree.element("Buyer").name.namespace).isEqualTo("urn:ugly:order")
            // party.xsd leaves elementFormDefault unqualified.
            assertThat(tree.elements("Name").map { it.name.namespace }).containsOnlyNulls()
            // Imported from a schema without targetNamespace.
            assertThat(tree.element("Remark").name).isEqualTo(QName(null, "Remark"))
        }

        @Test
        fun `resolves a qualified attribute from another namespace`() {
            val version = tree.roots.single().attributes.filterIsInstance<AttributeNode>().single().decl
            assertThat(version.name).isEqualTo(QName("urn:ugly:common", "version"))
            assertThat(version.required).isTrue()
            assertThat(version.fixed).isEqualTo("1.0")
        }
    }

    @Nested
    inner class SubstitutionGroups {
        private val tree = Corpus.load("ugly/substitution-groups/main.xsd")

        @Test
        fun `lists every concrete member, including transitive and cross-namespace ones`() {
            val shapes = tree.nodes<SubstitutionGroupNode>().first { it.head.name.localName == "Shape" }

            assertThat(shapes.occurs).isEqualTo(Occurs(1, null))
            assertThat(shapes.members.map { (it as ElementNode).name }).containsExactly(
                QName("urn:ugly:subst", "Circle"),
                QName("urn:ugly:subst", "RoundedSquare"),
                QName("urn:ugly:subst", "Square"),
                QName("urn:ugly:subst", "Triangle"),
                QName("urn:ugly:subst-ext", "Hexagon"),
            )
        }

        @Test
        fun `includes a non-abstract head among its members`() {
            val notes = tree.nodes<SubstitutionGroupNode>().first { it.head.name.localName == "Note" }

            assertThat(notes.occurs).isEqualTo(Occurs(0, 1))
            assertThat(notes.members.map { (it as ElementNode).name.localName }).containsExactly("Note", "UrgentNote")
        }

        @Test
        fun `records the substitution model`() {
            assertThat(tree.model.substitutionGroups[QName("urn:ugly:subst", "Polygon")])
                .containsExactly(QName("urn:ugly:subst", "Triangle"))
            assertThat(tree.model.globalElement(QName("urn:ugly:subst", "RoundedSquare")).substitutionGroup)
                .isEqualTo(QName("urn:ugly:subst", "Square"))
        }
    }

    @Nested
    inner class AnonymousTypes {
        private val tree = Corpus.load("ugly/anonymous-types/main.xsd")

        @Test
        fun `gives each anonymous complex type its own identity`() {
            val types = listOf("Catalog", "Item", "Dimensions", "Width").map { tree.element(it).complexType!!.id }

            assertThat(types).allMatch { it.isAnonymous }.doesNotHaveDuplicates()
            assertThat(tree.element("Item").typeName).isNull()
        }

        @Test
        fun `keeps anonymous simple types, lists and unions`() {
            val price = tree.element("Price").simpleType!!
            assertThat(price.isAnonymous).isTrue()
            assertThat(price.builtinBase).isEqualTo("decimal")
            assertThat(price.facets.maxInclusive).isEqualTo("99999.99")

            val tags = tree.element("Tags").simpleType!!
            assertThat(tags.variety).isEqualTo(Variety.LIST)
            assertThat(tags.itemType!!.name).isEqualTo(QName(XSD, "token"))

            val size = tree.element("Size").simpleType!!
            assertThat(size.variety).isEqualTo(Variety.UNION)
            assertThat(size.memberTypes.map { it.name }).containsExactly(QName(XSD, "positiveInteger"), null)
            assertThat(size.memberTypes[1].facets.enumerations).containsExactly("S", "M", "L")
        }

        @Test
        fun `keeps simple content with an anonymous attribute type`() {
            val width = tree.element("Width")
            assertThat(width.contentKind).isEqualTo(ContentKind.SIMPLE)
            assertThat(width.simpleType!!.builtinBase).isEqualTo("decimal")
            val unit = width.attributes.filterIsInstance<AttributeNode>().single().decl
            assertThat(unit.type.facets.enumerations).containsExactly("cm", "in")
        }
    }

    @Nested
    inner class Recursion {
        private val tree = Corpus.load("ugly/recursion/main.xsd")

        @Test
        fun `stops every recursion with a reference node`() {
            // allNodes fails if the tree does not end.
            val recursions = tree.nodes<RecursionNode>().associateBy { it.name.localName }

            assertThat(recursions.keys).containsExactlyInAnyOrder("SubPart", "Tree", "Expr", "SubList")
            assertThat(recursions.getValue("SubPart").distance).isEqualTo(1)
            assertThat(recursions.getValue("SubPart").occurs).isEqualTo(Occurs(0, null))
            assertThat(recursions.getValue("SubPart").targetType).isEqualTo(TypeId(QName(NS, "PartType")))
            // Tree -> Branch -> Tree
            assertThat(recursions.getValue("Tree").distance).isEqualTo(2)
            assertThat(recursions.getValue("Expr").targetType.isAnonymous).isTrue()
            assertThat(recursions.getValue("Expr").occurs).isEqualTo(Occurs(2, 2))
            assertThat(recursions.getValue("SubList").targetType).isEqualTo(TypeId(QName(NS, "ListType")))
        }

        @Test
        fun `expands the same type again where it is not recursive`() {
            // Model contains Tree, and so does Branch; only the nested one is a recursion.
            assertThat(tree.elements("Tree")).hasSize(1)
            assertThat(tree.roots.map { it.name.localName }).containsExactly("Model", "Expr")
        }
    }

    @Nested
    inner class ChoiceInSequence {
        private val tree = Corpus.load("ugly/choice-in-sequence/main.xsd")

        @Test
        fun `keeps choices as explicit nodes inside the sequence`() {
            val sequence = tree.roots.single().content as CompositorNode
            assertThat(sequence.compositor).isEqualTo(Compositor.SEQUENCE)
            assertThat(sequence.children.map(::describe)).containsExactly(
                "element Id [1..1]",
                "choice [1..1]",
                "element Amount [1..1]",
                "choice [0..*]",
                "choice [1..1]",
                "choice [0..1]",
                "element BillingAddress [1..1]",
            )
        }

        @Test
        fun `keeps sequences and choices nested in a choice`() {
            val sequence = tree.roots.single().content as CompositorNode
            val payment = sequence.children[1] as CompositorNode
            assertThat(payment.children.map(::describe))
                .containsExactly("element Card [1..1]", "element Iban [1..1]", "sequence [1..1]")

            val timing = sequence.children[4] as CompositorNode
            assertThat(timing.children.map(::describe)).containsExactly("choice [1..1]", "element Scheduled [1..1]")
        }

        @Test
        fun `represents xs all`() {
            val address = tree.element("BillingAddress").content as CompositorNode
            assertThat(address.compositor).isEqualTo(Compositor.ALL)
            assertThat(address.children.map(::describe))
                .containsExactly("element Street [1..1]", "element City [1..1]", "element Zip [0..1]")
        }
    }

    @Nested
    inner class Wildcards {
        private val tree = Corpus.load("ugly/wildcards/main.xsd")
        private val ns = "urn:ugly:wildcards"

        private fun anyIn(element: String) = tree.element(element).let { (it.content as CompositorNode).children.single() as AnyNode }

        @Test
        fun `represents each namespace constraint and processContents`() {
            assertThat(anyIn("Header").wildcard)
                .isEqualTo(Wildcard(NamespaceConstraint.Not(listOf(ns, null)), ProcessContents.LAX))
            assertThat(anyIn("Header").occurs).isEqualTo(Occurs(0, null))
            assertThat(anyIn("Body").wildcard).isEqualTo(Wildcard(NamespaceConstraint.Any, ProcessContents.STRICT))
            val extensions = anyIn("Extensions").wildcard
            assertThat(extensions.processContents).isEqualTo(ProcessContents.SKIP)
            assertThat((extensions.namespaces as NamespaceConstraint.OneOf).namespaces)
                .containsExactlyInAnyOrder(ns, "urn:ugly:other", null)
        }

        @Test
        fun `represents anyAttribute`() {
            val anyAttribute = tree.roots.single().attributes.single() as AnyAttributeNode
            assertThat(anyAttribute.wildcard.processContents).isEqualTo(ProcessContents.LAX)
        }

        @Test
        fun `gives an untyped element xs anyType`() {
            val untyped = tree.element("Untyped")
            assertThat(untyped.typeName).isEqualTo(QName(XSD, "anyType"))
            assertThat(untyped.contentKind).isEqualTo(ContentKind.MIXED)
            assertThat(anyIn("Untyped").wildcard).isEqualTo(Wildcard(NamespaceConstraint.Any, ProcessContents.LAX))
        }
    }

    @Nested
    inner class MixedContent {
        private val tree = Corpus.load("ugly/mixed-content/main.xsd")

        @Test
        fun `marks mixed content and keeps the interleaved elements`() {
            val paragraph = tree.element("Paragraph")
            assertThat(paragraph.contentKind).isEqualTo(ContentKind.MIXED)
            val inline = paragraph.content as CompositorNode
            assertThat(inline.compositor).isEqualTo(Compositor.CHOICE)
            assertThat(inline.occurs).isEqualTo(Occurs(0, null))
            assertThat(inline.children.map(::describe))
                .containsExactly("recursion b [1..1]", "recursion i [1..1]", "element link [1..1]")
        }

        @Test
        fun `has no content model for text-only mixed content`() {
            val title = tree.element("Title")
            assertThat(title.contentKind).isEqualTo(ContentKind.MIXED)
            assertThat(title.content).isNull()
            assertThat(title.children).hasSize(1)
        }
    }

    @Nested
    inner class FacetsAndValueConstraints {
        private val tree = Corpus.load("ugly/facets/main.xsd")

        private fun facets(element: String) = tree.element(element).simpleType!!.facets

        @Test
        fun `keeps every facet`() {
            assertThat(facets("Status").enumerations).containsExactly("NEW", "OPEN", "CLOSED")
            assertThat(facets("Country").length).isEqualTo(2)
            assertThat(facets("Name").minLength).isEqualTo(1)
            assertThat(facets("Name").maxLength).isEqualTo(35)
            assertThat(facets("Amount").totalDigits).isEqualTo(18)
            assertThat(facets("Amount").fractionDigits).isEqualTo(2)
            assertThat(facets("Amount").minInclusive).isNotNull()
            assertThat(facets("Amount").maxInclusive).isNotNull()
            assertThat(facets("Discount").minExclusive).isNotNull()
            assertThat(facets("Discount").maxExclusive).isNotNull()
        }

        @Test
        fun `keeps one pattern per derivation step`() {
            val id = tree.element("Id").simpleType!!
            assertThat(id.facets.patterns).containsExactlyInAnyOrder("[A-Z0-9]+", ".{1,5}")
            assertThat(id.facets.maxLength).isEqualTo(5)
            assertThat(id.builtinBase).isEqualTo("string")
        }

        @Test
        fun `keeps the implicit facets of built-in types`() {
            assertThat(facets("Count").minInclusive).isEqualTo("0")
            assertThat(facets("Count").maxInclusive).isEqualTo("255")
        }

        @Test
        fun `keeps nillable, default and fixed`() {
            assertThat(tree.element("Status").decl.default).isEqualTo("NEW")
            assertThat(tree.element("Country").decl.fixed).isEqualTo("MA")
            assertThat(tree.element("Name").decl.nillable).isTrue()

            val attributes = tree.roots.single().attributes.filterIsInstance<AttributeNode>().associateBy { it.name.localName }
            assertThat(attributes.getValue("version").decl.fixed).isEqualTo("2")
            assertThat(attributes.getValue("priority").decl.default).isEqualTo("0")
            assertThat(attributes.getValue("status").decl.required).isTrue()
        }
    }

    @Nested
    inner class LoaderBehaviour {
        @Test
        fun `does not fetch remote schema locations`() {
            val tree = SchemaLoader().load(
                Corpus.path("ugly/facets/main.xsd"),
                content = """
                    <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                        <xs:import namespace="urn:remote" schemaLocation="https://example.invalid/remote.xsd"/>
                        <xs:element name="Local" type="xs:string"/>
                    </xs:schema>
                """.trimIndent(),
            )

            assertThat(tree.diagnostics.map { it.message })
                .anyMatch { it.startsWith("Remote reference not loaded") && "example.invalid" in it }
            assertThat(tree.rootCandidates).containsExactly(QName(null, "Local"))
        }

        @Test
        fun `propagates cancellation thrown while resolving includes`() {
            var calls = 0
            val loading = {
                SchemaLoader().load(Corpus.path("ugly/include-chain/main.xsd")) {
                    if (++calls == 3) throw CancelledForTest()
                }
            }

            assertThatThrownBy { loading() }.isInstanceOf(CancelledForTest::class.java)
        }
    }

    private class CancelledForTest : RuntimeException()

    private fun localNames(element: ElementNode): List<String> =
        allNodes(element).filterIsInstance<ElementNode>().drop(1).map { it.name.localName }

    private fun allNodes(node: SchemaNode): List<SchemaNode> = listOf(node) + node.children.flatMap(::allNodes)

    private fun describe(node: SchemaNode): String = when (node) {
        is ElementNode -> "element ${node.name.localName} ${node.occurs}"
        is RecursionNode -> "recursion ${node.name.localName} ${node.occurs}"
        is CompositorNode -> "${node.compositor.xsdName} ${node.occurs}"
        else -> node.toString()
    }

    private companion object {
        const val XSD = "http://www.w3.org/2001/XMLSchema"
        const val NS = "urn:ugly:recursion"
    }
}
