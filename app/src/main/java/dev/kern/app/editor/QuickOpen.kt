package dev.kern.app.editor

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Go to file: type a few letters of a name, tap the match. On a phone this is faster than
 * any tree, and it is what VS Code's Ctrl+P is for.
 */
object QuickOpenIndex {

    /** Folders nobody opens files from, and that are often enormous. */
    private val SKIP = setOf(".git", "node_modules", ".venv", "venv", "__pycache__", ".gradle", "build", "target", ".next", "dist", ".cache")
    private const val LIMIT = 20_000
    private const val CACHE_MS = 15_000L

    private class Walk(val root: String, val at: Long, val files: List<String>)

    /**
     * The last walk, reused for a few seconds: go to file is often opened twice in quick
     * succession - a mistyped name, a second file - and each open walked up to 20,000
     * files again before the first letter could match anything.
     */
    @Volatile
    private var last: Walk? = null

    /**
     * Every file under [root], as paths relative to it. Symlinks are not followed into
     * folders - one to `/` or back up the tree walked the whole rootfs, or the app's own
     * files - and a linked file is listed only when it points inside the rootfs.
     */
    fun files(context: Context, root: String): List<String> {
        val now = SystemClock.elapsedRealtime()
        last?.let { if (it.root == root && now - it.at < CACHE_MS) return it.files }
        val out = ArrayList<String>()
        val start = GuestFiles.host(context, root)
        if (!GuestFiles.contained(context, start)) return out
        runCatching {
            val base = start.canonicalFile.toPath()
            Files.walkFileTree(
                base,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                        if (dir != base && dir.fileName.toString() in SKIP) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val listed = attrs.isRegularFile ||
                            (attrs.isSymbolicLink && Files.isRegularFile(file) && GuestFiles.contained(context, file.toFile()))
                        if (listed) out += base.relativize(file).toString().replace(File.separatorChar, '/')
                        return if (out.size >= LIMIT) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                    }

                    // An unreadable folder is skipped, not the end of the walk.
                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
        }
        last = Walk(root, now, out)
        return out
    }

    /** The best [limit] of [files] for [query], best first. */
    fun rank(files: List<String>, query: String, limit: Int = 60): List<String> =
        files.mapNotNull { path -> score(path, query)?.let { path to it } }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }

    /**
     * Subsequence match, scored so that what people mean ranks first: matches in the file
     * name beat matches in the folders, consecutive letters beat scattered ones, and
     * shorter paths win ties. Null when [query] is not a subsequence of [path] at all.
     */
    fun score(path: String, query: String): Int? {
        if (query.isEmpty()) return 0
        val p = path.lowercase()
        val q = query.lowercase()
        val nameStart = p.lastIndexOf('/') + 1
        var score = 0
        var pi = 0
        var last = -2
        for (c in q) {
            val found = p.indexOf(c, pi)
            if (found < 0) return null
            score += when {
                found == last + 1 -> 8
                found == nameStart || (found > 0 && p[found - 1] in "/_-. ") -> 6
                else -> 1
            }
            if (found >= nameStart) score += 3
            last = found
            pi = found + 1
        }
        return score * 100 - path.length
    }
}

@Composable
fun QuickOpen(root: String, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<String>?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(root) {
        all = withContext(Dispatchers.IO) { QuickOpenIndex.files(context, root) }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // Ranked off the main thread: scoring 20,000 paths on every keystroke stalled typing.
    // A newer keystroke cancels the ranking still under way for the last one.
    var matches by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(query, all) {
        val files = all ?: return@LaunchedEffect
        matches = withContext(Dispatchers.Default) { QuickOpenIndex.rank(files, query) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Go to file") },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { matches.firstOrNull()?.let { onOpen("$root/$it") } }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        if (all == null) {
            Text("Indexing…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            items(matches) { rel ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen("$root/$rel") }
                        .padding(horizontal = 8.dp, vertical = 9.dp),
                ) {
                    Text(rel.substringAfterLast('/'), fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
                    if ('/' in rel) {
                        Text(
                            rel.substringBeforeLast('/'),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Text(
            "Close",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp),
        )
    }
}
