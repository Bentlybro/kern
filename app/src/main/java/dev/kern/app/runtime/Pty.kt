package dev.kern.app.runtime

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/** JNI bindings for the app's own pseudo-terminal (see cpp/pty.c). */
object Pty {
    init {
        System.loadLibrary("kern_pty")
    }

    /** @return the pty master fd, or -1 on failure. Writes the child pid into [pidOut]. */
    @JvmStatic
    external fun createSubprocess(
        command: String,
        argv: Array<String>,
        envp: Array<String>,
        cwd: String?,
        columns: Int,
        rows: Int,
        pidOut: IntArray,
    ): Int

    @JvmStatic
    external fun setWindowSize(fd: Int, columns: Int, rows: Int)

    /** Blocks until the child exits; returns its exit code, or -signal if killed. */
    @JvmStatic
    external fun waitFor(pid: Int): Int

    /** SIGHUP+SIGKILL the child's process group (it is its own session leader). */
    @JvmStatic
    external fun killProcessGroup(pid: Int)

    @JvmStatic
    external fun closeFd(fd: Int)
}

/**
 * A running child on a pty. Owns the fd; [close] tears down both the process and the fd.
 */
class PtyProcess private constructor(
    val fd: Int,
    val pid: Int,
) {
    private val parcel: ParcelFileDescriptor = ParcelFileDescriptor.adoptFd(fd)
    val input: FileInputStream = FileInputStream(parcel.fileDescriptor)
    val output: FileOutputStream = FileOutputStream(parcel.fileDescriptor)

    // Kotlin already exposes getInput()/getOutput()/getPid() to Java from the properties
    // above, which is what the vendored terminal code calls.

    fun resize(columns: Int, rows: Int) {
        if (fd >= 0) Pty.setWindowSize(fd, columns, rows)
    }

    fun waitFor(): Int = Pty.waitFor(pid)

    fun close() {
        // Kill the group, not just proot: otherwise the guest's children survive.
        runCatching { Pty.killProcessGroup(pid) }
        runCatching { parcel.close() }
    }

    /**
     * Drain the pty until the child goes away, keeping whatever it printed.
     *
     * Reading a pty master after its child exits raises EIO rather than returning EOF, so
     * a plain `readBytes()` both throws *and* discards everything already read. Accumulate
     * chunk by chunk and treat the error as end-of-stream. [drain] and [drainInBackground]
     * are the same loop for callers that only need the buffer emptied.
     */
    fun readUntilClosed(): String {
        val out = StringBuilder()
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.append(String(buffer, 0, read, Charsets.UTF_8))
            }
        } catch (e: Exception) {
            // EIO here means the child exited; whatever we collected is the output.
        }
        return out.toString().trim()
    }

    /** [readUntilClosed] for a caller that needs the pty emptied rather than read. */
    fun drain() {
        runCatching {
            val buffer = ByteArray(4096)
            while (input.read(buffer) >= 0) { /* discard */ }
        }
    }

    /** [drain] on a daemon thread called [name], for a child that outlives this call. */
    /**
     * Drain on a daemon thread, calling [onClosed] once the pty reaches EIO.
     *
     * The drain ending is the one honest signal that the child is gone: a pty master raises
     * EIO instead of EOF when its last slave closes. A caller holding a long-lived process
     * has no other way to notice it died, and treating a dead handle as a live one is worse
     * than having no handle at all.
     */
    fun drainInBackground(name: String, onClosed: (() -> Unit)? = null) {
        Thread({
            drain()
            onClosed?.invoke()
        }, name).apply { isDaemon = true }.start()
    }

    companion object {
        /**
         * Spawn [command] on a fresh pty.
         *
         * @param env environment as a map; converted to the `KEY=value` form execve wants.
         */
        fun spawn(
            command: String,
            argv: List<String>,
            env: Map<String, String>,
            cwd: String? = null,
            columns: Int = 80,
            rows: Int = 24,
        ): PtyProcess? {
            val pidOut = IntArray(1)
            val fd = Pty.createSubprocess(
                command,
                argv.toTypedArray(),
                env.map { "${it.key}=${it.value}" }.toTypedArray(),
                cwd,
                columns,
                rows,
                pidOut,
            )
            if (fd < 0) return null
            return PtyProcess(fd, pidOut[0])
        }
    }
}
