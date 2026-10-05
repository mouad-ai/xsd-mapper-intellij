package com.github.mouadai.xsdmapper.plugin

import com.github.mouadai.xsdmapper.core.schema.SchemaDiagnostic
import com.github.mouadai.xsdmapper.core.schema.SchemaLoader
import com.github.mouadai.xsdmapper.core.schema.SchemaTree
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.nio.file.Path

/**
 * Shows the [SchemaTree] of one .xsd file. Follows the selected editor, and loads on request from
 * [ShowSchemaTreeAction]. Loading runs in a cancellable background task; the tree then expands lazily.
 */
class SchemaTreePanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val status = JBLabel("Open an .xsd file or right-click one and choose Show Schema Tree.").apply {
        border = JBUI.Borders.empty(4, 8)
    }
    private val tree = Tree(SchemaTreeModel.EMPTY).apply {
        isRootVisible = true
        showsRootHandles = true
        cellRenderer = SchemaTreeCellRenderer()
    }

    private var shownFile: VirtualFile? = null
    @Volatile
    private var loading: ProgressIndicator? = null

    /** Incremented per load, so a slow load that finishes after a newer one is dropped. */
    private var generation = 0

    init {
        toolbar = status
        setContent(ScrollPaneFactory.createScrollPane(tree, true))

        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val file = event.newFile ?: return
                    if (isXsd(file) && file != shownFile) load(file)
                }
            },
        )
    }

    /** Loads [file] in the background and shows its tree. Must be called on the EDT. */
    fun load(file: VirtualFile) {
        val path = if (file.isInLocalFileSystem) file.toNioPath() else null
        if (path == null) {
            status.text = "${file.name}: only schemas on the local file system can be loaded."
            return
        }
        loading?.cancel()
        shownFile = file
        val current = ++generation
        status.text = "Loading ${file.name}…"
        val unsaved = unsavedText(file)

        object : Task.Backgroundable(project, "Loading schema ${file.name}", true) {
            private var result: SchemaTree? = null

            override fun run(indicator: ProgressIndicator) {
                loading = indicator
                indicator.isIndeterminate = true
                result = loadInBackground(path, unsaved, indicator)
            }

            override fun onSuccess() {
                val schema = result ?: return
                if (current == generation) show(file, schema)
            }

            override fun onCancel() {
                if (current == generation) status.text = "Loading ${file.name} was cancelled."
            }

            override fun onThrowable(error: Throwable) {
                if (current == generation) status.text = "Could not load ${file.name}: ${error.message}"
            }
        }.queue()
    }

    private fun loadInBackground(path: Path, content: String?, indicator: ProgressIndicator): SchemaTree {
        val schema = SchemaLoader().load(path, content) { indicator.checkCanceled() }
        // Build the first levels here rather than on the EDT.
        schema.roots.forEach { root -> root.children.forEach { it.children } }
        return schema
    }

    private fun show(file: VirtualFile, schema: SchemaTree) {
        tree.model = SchemaTreeModel(SchemaTreeModel.Root(file.name, schema))
        tree.expandRow(0)
        if (schema.roots.size == 1) tree.expandRow(tree.rowCount - 1)

        val problems = schema.diagnostics.count { it.severity != SchemaDiagnostic.Severity.WARNING }
        status.text = buildString {
            append(file.name).append(": ")
            append(schema.roots.size).append(if (schema.roots.size == 1) " root element" else " root elements")
            if (problems > 0) append(", ").append(problems).append(if (problems == 1) " error" else " errors")
        }
    }

    /** The editor's text when it has unsaved changes, so the tree reflects what the user sees. */
    private fun unsavedText(file: VirtualFile): String? {
        val documents = FileDocumentManager.getInstance()
        val document = documents.getCachedDocument(file) ?: return null
        return if (documents.isDocumentUnsaved(document)) runReadAction { document.text } else null
    }

    override fun dispose() {
        loading?.cancel()
    }
}
