package com.github.mouadai.xsdmapper.core.schema

import java.nio.file.Files
import java.nio.file.Path

/** Access to /testdata and to the snapshot directory, located through system properties set by Gradle. */
object Corpus {
    val root: Path = Path.of(requireNotNull(System.getProperty("xsdmapper.testdata")) { "xsdmapper.testdata not set" })

    /** Entry-point schemas listed in testdata/entrypoints.txt, relative to /testdata. */
    val entryPoints: List<String> = Files.readAllLines(root.resolve("entrypoints.txt"))
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    private val loaded = HashMap<String, SchemaTree>()

    fun path(relative: String): Path = root.resolve(relative)

    /** Loads (and caches) a corpus schema. */
    @Synchronized
    fun load(relative: String): SchemaTree = loaded.getOrPut(relative) { SchemaLoader().load(path(relative)) }
}

/** Walks the tree breadth-first, visiting at most [budget] nodes. Returns the number of nodes visited. */
fun walk(tree: SchemaTree, budget: Int, visit: (SchemaNode) -> Unit = {}): Int {
    val queue = ArrayDeque<SchemaNode>(tree.roots)
    var count = 0
    while (queue.isNotEmpty() && count < budget) {
        val node = queue.removeFirst()
        visit(node)
        count++
        queue.addAll(node.children)
    }
    return count
}

/** Every node reachable from the roots. Only for schemas whose expanded tree is known to be small. */
fun allNodes(tree: SchemaTree): List<SchemaNode> {
    val nodes = mutableListOf<SchemaNode>()
    val visited = walk(tree, 100_000) { nodes += it }
    check(visited < 100_000) { "Tree of ${tree.location} is too large to walk completely" }
    return nodes
}

inline fun <reified T : SchemaNode> SchemaTree.nodes(): List<T> = allNodes(this).filterIsInstance<T>()

fun SchemaTree.element(localName: String): ElementNode =
    nodes<ElementNode>().first { it.name.localName == localName }

fun SchemaTree.elements(localName: String): List<ElementNode> =
    nodes<ElementNode>().filter { it.name.localName == localName }
