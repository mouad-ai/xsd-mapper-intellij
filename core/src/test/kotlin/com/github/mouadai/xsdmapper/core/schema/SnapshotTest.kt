package com.github.mouadai.xsdmapper.core.schema

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * Compares the printed tree of a few schemas with the files in core/src/test/snapshots.
 * Run `./gradlew :core:test -Psnapshots.update` to rewrite them after an intended change, and review the diff.
 */
class SnapshotTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "corpus/iso20022/pain.001.001.09.xsd, pain.001.001.09.txt, 2147483647",
        "corpus/ubl-2.1/xsd/maindoc/UBL-Invoice-2.1.xsd, ubl-invoice-depth2.txt, 2",
        "ugly/cross-namespace-imports/main.xsd, cross-namespace-imports.txt, 2147483647",
        "ugly/substitution-groups/main.xsd, substitution-groups.txt, 2147483647",
        "ugly/recursion/main.xsd, recursion.txt, 2147483647",
    )
    fun `tree matches snapshot`(schema: String, snapshot: String, maxDepth: Int) {
        val actual = SchemaTreePrinter(maxDepth).print(Corpus.load(schema))
        val file = snapshotDir.resolve(snapshot)

        if (update || !Files.exists(file)) {
            Files.createDirectories(snapshotDir)
            Files.writeString(file, actual)
            if (!update) throw AssertionError("Snapshot $snapshot did not exist and was written; review and commit it")
        }
        assertThat(actual).isEqualTo(Files.readString(file))
    }

    private companion object {
        val snapshotDir: Path = Path.of(requireNotNull(System.getProperty("xsdmapper.snapshots")))
        val update = System.getProperty("xsdmapper.snapshots.update").toBoolean()
    }
}
