package com.github.mouadai.xsdmapper.core.schema

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class CorpusLoadTest {

    @ParameterizedTest
    @MethodSource("entryPoints")
    fun `loads without errors`(schema: String) {
        val tree = Corpus.load(schema)

        assertThat(tree.diagnostics.filter { it.severity != SchemaDiagnostic.Severity.WARNING })
            .describedAs("diagnostics of %s", schema)
            .isEmpty()
        assertThat(tree.rootCandidates).describedAs("root candidates of %s", schema).isNotEmpty()
    }

    @ParameterizedTest
    @MethodSource("entryPoints")
    fun `expands a large part of the tree`(schema: String) {
        val tree = Corpus.load(schema)

        val visited = walk(tree, 20_000)

        assertThat(visited).isGreaterThan(tree.rootCandidates.size)
    }

    @Test
    fun `finds the document roots of the real-world schemas`() {
        fun roots(schema: String) = Corpus.load(schema).rootCandidates.map { it.localName }

        assertThat(roots("corpus/ubl-2.1/xsd/maindoc/UBL-Invoice-2.1.xsd")).containsExactly("Invoice")
        assertThat(roots("corpus/ubl-2.1/xsd/maindoc/UBL-CreditNote-2.1.xsd")).containsExactly("CreditNote")
        assertThat(roots("corpus/cii-d16b/data/standard/CrossIndustryInvoice_100pD16B.xsd"))
            .containsExactly("CrossIndustryInvoice")
        assertThat(roots("corpus/iso20022/pain.001.001.09.xsd")).containsExactly("Document")
        assertThat(roots("corpus/iso20022/camt.053.001.08.xsd")).containsExactly("Document")
    }

    @Test
    fun `reports a missing file instead of throwing`() {
        val tree = SchemaLoader().load(Corpus.path("does-not-exist.xsd"))

        assertThat(tree.hasErrors).isTrue()
        assertThat(tree.roots).isEmpty()
    }

    @Test
    fun `reports a broken reference as an error`() {
        val tree = SchemaLoader().load(
            Corpus.path("ugly/recursion/main.xsd"),
            content = """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                    <xs:element name="Broken" type="MissingType"/>
                </xs:schema>
            """.trimIndent(),
        )

        assertThat(tree.hasErrors).isTrue()
        assertThat(tree.diagnostics.map { it.message }).anyMatch { "MissingType" in it }
    }

    companion object {
        @JvmStatic
        fun entryPoints(): List<String> = Corpus.entryPoints
    }
}
