package dev.kern.app.projects

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kern.app.core.AppScope
import dev.kern.app.editor.NativeEditor
import dev.kern.app.workbench.WorkbenchWebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the Projects screen does - create, clone, delete - apart from how it looks.
 *
 * The guest work always runs on the app scope: leaving the screen mid-create killed git
 * init and left the folder behind, a clone abandoned by a posture change opened nothing,
 * and an rm -rf of a big repository takes long enough to be interrupted. Only the
 * reporting belongs to the screen's [scope]; the consequences (opening the new project)
 * ride on the work itself.
 */
class ProjectsModel(private val app: Context, private val scope: CoroutineScope) {

    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var reload by mutableIntStateOf(0)
        private set

    /** git's own progress lines; they arrive on an IO thread, hence a flow. */
    val progress = MutableStateFlow<String?>(null)

    private var cloneJob: Job? = null

    val isCloning: Boolean get() = cloneJob?.isActive == true

    /** Create [name] and open it. [onDone] gets its path on success, while the screen is up. */
    fun create(name: String, initGit: Boolean, onDone: (String) -> Unit) {
        busy = "Creating $name…"
        message = null
        val work = AppScope.start {
            ProjectRepository.create(app, name, initGit).also { openOnSuccess(it) }
        }
        scope.launch { report(work.await(), "Created", onDone) }
    }

    fun clone(url: String, shallow: Boolean, onDone: (String) -> Unit) {
        busy = "Cloning…"
        message = null
        progress.value = null
        val work = AppScope.start {
            ProjectRepository.clone(app, url, shallow = shallow, onProgress = { progress.value = it })
                .also { openOnSuccess(it) }
        }
        cloneJob = work
        scope.launch {
            // join, not await: a cancelled clone must not take this reporting down too.
            work.join()
            progress.value = null
            if (work.isCancelled) {
                busy = null
                message = "Clone cancelled"
            } else {
                report(work.await(), "Cloned", onDone)
            }
        }
    }

    /** The repository takes the half-cloned folder back out, so a retry works. */
    fun cancelClone() {
        busy = "Cancelling…"
        cloneJob?.cancel()
    }

    fun delete(name: String) {
        busy = "Deleting $name…"
        message = null
        // Its tabs close first, inside the same app-scoped job as the rm: a save still
        // queued for one of them would otherwise put the deleted folder back.
        val work = AppScope.start {
            var outcome: ProjectRepository.Outcome = ProjectRepository.Outcome.Failure("${name.trim()} has unsaved changes - resolve them first")
            NativeEditor.moving(app, "${ProjectRepository.PROJECTS_DIR}/${name.trim()}", null) {
                outcome = ProjectRepository.delete(app, name)
                outcome is ProjectRepository.Outcome.Success
            }
            outcome
        }
        scope.launch { report(work.await(), "Deleted") {} }
    }

    private fun openOnSuccess(outcome: ProjectRepository.Outcome) {
        if (outcome is ProjectRepository.Outcome.Success) {
            ProjectRepository.rememberOpened(app, outcome.path)
            WorkbenchWebView.openFolder(outcome.path)
        }
    }

    private fun report(outcome: ProjectRepository.Outcome, verb: String, onDone: (String) -> Unit) {
        busy = null
        when (outcome) {
            is ProjectRepository.Outcome.Success -> {
                message = "$verb ${outcome.name}"
                reload++
                onDone(outcome.path)
            }
            is ProjectRepository.Outcome.Failure -> message = outcome.message
        }
    }
}
