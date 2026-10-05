package com.github.mouadai.xsdmapper.core.schema

import org.apache.xerces.dom.DOMInputImpl
import org.apache.xerces.impl.xs.XSImplementationImpl
import org.apache.xerces.xs.XSModel
import org.w3c.dom.DOMError
import org.w3c.dom.DOMErrorHandler
import org.w3c.dom.ls.LSInput
import org.w3c.dom.ls.LSResourceResolver
import java.io.StringReader
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads an XSD with Xerces and converts it to a [SchemaTree].
 *
 * `xs:include`, `xs:import` and `xs:redefine` are resolved relative to the document that contains them,
 * starting from the schema file's own location. Imports without a `schemaLocation` are not resolved.
 */
class SchemaLoader(private val options: Options = Options()) {

    data class Options(
        /** Fetch `http(s):` schema locations. Off by default so loading never touches the network. */
        val allowRemote: Boolean = false,
    )

    /**
     * Loads the schema at [location]. When [content] is given it is used instead of the file's contents on disk
     * (an unsaved editor buffer, say); relative references still resolve against [location].
     *
     * [checkCanceled] is called regularly and may throw to abort loading; the exception propagates unchanged.
     *
     * Problems in the schema do not throw: they are reported in [SchemaTree.diagnostics].
     */
    fun load(location: Path, content: String? = null, checkCanceled: () -> Unit = {}): SchemaTree {
        val uri = location.toAbsolutePath().normalize().toUri()
        val diagnostics = mutableListOf<SchemaDiagnostic>()

        if (content == null && !Files.isRegularFile(location)) {
            diagnostics += SchemaDiagnostic(SchemaDiagnostic.Severity.FATAL, "Schema file not found", uri.toString())
            return SchemaTree(uri, null, SchemaModel.EMPTY, emptyList(), diagnostics)
        }

        checkCanceled()
        val xsModel = parse(uri, content, diagnostics, checkCanceled)
            ?: return SchemaTree(uri, null, SchemaModel.EMPTY, emptyList(), diagnostics)
        val targetNamespace = entryNamespace(xsModel, uri)

        val model = XsModelConverter(xsModel, checkCanceled).convert()
        return SchemaTree(uri, targetNamespace, model, rootCandidates(model, targetNamespace), diagnostics)
    }

    private fun parse(
        uri: URI,
        content: String?,
        diagnostics: MutableList<SchemaDiagnostic>,
        checkCanceled: () -> Unit,
    ): XSModel? {
        val loader = XSImplementationImpl().createXSLoader(null)
        val config = loader.config
        val resolver = Resolver(options, diagnostics, checkCanceled)
        config.setParameter("error-handler", CollectingErrorHandler(diagnostics))
        config.setParameter("resource-resolver", resolver)
        // A namespace split over several documents (imported from different places) is only fully loaded
        // when every schemaLocation is honoured, not just the first one seen for that namespace.
        config.setParameter("http://apache.org/xml/features/honour-all-schemaLocations", true)

        val input = DOMInputImpl().apply {
            systemId = uri.toString()
            if (content != null) characterStream = StringReader(content)
        }
        val model = loader.load(input)
        resolver.cancellation?.let { throw it }
        return model
    }

    /** The namespace of the entry document, found among the documents Xerces loaded. */
    private fun entryNamespace(xsModel: XSModel, uri: URI): String? {
        val entry = comparableLocation(uri.toString())
        val items = xsModel.namespaceItems
        for (i in 0 until items.length) {
            val item = items.item(i)
            val locations = item.documentLocations
            for (j in 0 until locations.length) {
                if (comparableLocation(locations.item(j)) == entry) return item.schemaNamespace?.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /** Xerces spells file URIs as `file:/x` or `file:///x` depending on how they were reached. */
    private fun comparableLocation(location: String): String = try {
        val uri = URI(location)
        if (uri.scheme.equals("file", ignoreCase = true)) Path.of(uri).normalize().toString() else uri.normalize().toString()
    } catch (_: Exception) {
        location
    }

    private fun rootCandidates(model: SchemaModel, targetNamespace: String?): List<QName> {
        val referenced = HashSet<QName>()
        model.complexTypes.values.forEach { type -> type.particle?.let { collectReferences(it, referenced) } }
        model.substitutionGroups.values.forEach(referenced::addAll)

        return model.globalElements.values
            .filter { it.name.namespace == targetNamespace && !it.isAbstract }
            .map { it.name }
            .sortedWith(compareBy<QName> { it in referenced }.thenBy { it })
    }

    private fun collectReferences(particle: Particle, into: MutableSet<QName>) {
        when (particle) {
            is ElementParticle -> if (particle.element.isGlobal) into += particle.element.name
            is GroupParticle -> particle.particles.forEach { collectReferences(it, into) }
            is WildcardParticle -> Unit
        }
    }

    private class CollectingErrorHandler(private val diagnostics: MutableList<SchemaDiagnostic>) : DOMErrorHandler {
        override fun handleError(error: DOMError): Boolean {
            val severity = when (error.severity) {
                DOMError.SEVERITY_WARNING -> SchemaDiagnostic.Severity.WARNING
                DOMError.SEVERITY_ERROR -> SchemaDiagnostic.Severity.ERROR
                else -> SchemaDiagnostic.Severity.FATAL
            }
            val location = error.location
            diagnostics += SchemaDiagnostic(
                severity,
                error.message ?: error.type ?: "Unknown error",
                location?.uri,
                location?.lineNumber ?: -1,
                location?.columnNumber ?: -1,
            )
            return true
        }
    }

    private class Resolver(
        private val options: Options,
        private val diagnostics: MutableList<SchemaDiagnostic>,
        private val checkCanceled: () -> Unit,
    ) : LSResourceResolver {
        var cancellation: Throwable? = null
            private set

        override fun resolveResource(
            type: String?,
            namespaceURI: String?,
            publicId: String?,
            systemId: String?,
            baseURI: String?,
        ): LSInput? {
            if (cancellation != null) return EMPTY_INPUT
            try {
                checkCanceled()
            } catch (e: Throwable) {
                // Xerces swallows exceptions thrown from a resolver, so remember it, let the load wind down on empty
                // documents, and rethrow it once Xerces returns.
                cancellation = e
                return EMPTY_INPUT
            }
            if (systemId == null) return null
            val resolved = try {
                if (baseURI != null) URI(baseURI).resolve(systemId) else URI(systemId)
            } catch (_: Exception) {
                return null
            }
            val scheme = resolved.scheme?.lowercase()
            if (!options.allowRemote && (scheme == "http" || scheme == "https")) {
                diagnostics += SchemaDiagnostic(
                    SchemaDiagnostic.Severity.WARNING,
                    "Remote reference not loaded (remote loading is off): $resolved",
                    baseURI,
                )
                // An empty document instead of a network fetch; Xerces reports what it could not resolve.
                return DOMInputImpl(publicId, resolved.toString(), baseURI, "", null)
            }
            // Returning null lets Xerces open the already resolved location itself.
            return null
        }

        private companion object {
            val EMPTY_INPUT: LSInput get() = DOMInputImpl(null, null, null, "", null)
        }
    }
}
