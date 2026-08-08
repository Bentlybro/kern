package dev.kern.app.runtime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The settings a Kern guest needs to be usable: apt, DNS, dpkg and the login environment.
 *
 * Kept apart from [RootfsInstaller] because configuring is not the same job as installing.
 * [apply] runs on every setup *and* every Repair, so a guest whose configuration something
 * else has since overwritten can be put back without reinstalling it.
 */
object GuestConfig {

    private const val TAG = "Kern"

    /**
     * Ubuntu 26.04 LTS, verified on-device against this PRoot build: fake root, apt,
     * dpkg's hard-link handling, and code-server all behave. Worth knowing that 26.04
     * ships uutils (Rust) coreutils rather than GNU — it caused no trouble in testing,
     * but it is the newest moving part if something odd ever turns up in a package's
     * install scripts.
     *
     * The codename lives next to the version because [apply] writes it into
     * sources.list, and the two drifting apart produces a rootfs that cannot install
     * anything.
     */
    const val UBUNTU_RELEASE = "26.04"
    private const val UBUNTU_CODENAME = "resolute"

    /**
     * Quieten the guest login.
     *
     * Android hands its own supplementary group IDs to every process, and they come
     * through PRoot into the guest, where `/etc/group` has no matching entries — so each
     * login prints "groups: cannot find name for group ID …". Naming them once fixes it,
     * and the IDs must be read from inside the guest because they belong to the running
     * process, not to the filesystem.
     */
    suspend fun polish(context: Context) {
        LinuxRuntime.run(
            context,
            """
            for g in ${'$'}(awk '/^Groups:/{${'$'}1=""; print}' /proc/self/status 2>/dev/null); do
              grep -q ":x:${'$'}g:" /etc/group 2>/dev/null || echo "android_${'$'}g:x:${'$'}g:" >> /etc/group
            done
            mkdir -p /root/projects
            git config --global --get init.defaultBranch >/dev/null 2>&1 || \
              git config --global init.defaultBranch main
            git config --global --get user.name >/dev/null 2>&1 || \
              git config --global user.name Kern
            git config --global --get user.email >/dev/null 2>&1 || \
              git config --global user.email kern@localhost
            """.trimIndent(),
            timeoutMs = 60_000,
        )
    }

    fun apply(context: Context) {
        val root = LinuxRuntime.rootfsDir(context)

        write(File(root, "etc/resolv.conf"), "nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        write(
            File(root, "etc/hosts"),
            "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n",
        )
        // arm64 lives on ports.ubuntu.com, not archive.ubuntu.com.
        write(
            File(root, "etc/apt/sources.list"),
            listOf(
                UBUNTU_CODENAME,
                "$UBUNTU_CODENAME-updates",
                "$UBUNTU_CODENAME-security",
            ).joinToString("\n") {
                "deb http://ports.ubuntu.com/ubuntu-ports $it main universe restricted multiverse"
            } + "\n",
        )
        // 24.04 also ships the same repositories in deb822 form. Leaving both in place
        // makes apt warn about every target being configured twice, so the one we do
        // not control goes; the list written above covers the same components.
        runCatching { File(root, "etc/apt/sources.list.d/ubuntu.sources").delete() }
        write(
            File(root, "etc/apt/apt.conf.d/99kern"),
            buildString {
                // apt drops privileges to _apt by default, which cannot work under PRoot.
                appendLine("APT::Sandbox::User \"root\";")
                // Pipelining and pdiffs are the classic causes of apt hanging under PRoot.
                appendLine("Acquire::http::Pipeline-Depth \"0\";")
                appendLine("Acquire::PDiffs \"false\";")
                appendLine("Acquire::Retries \"3\";")
                appendLine("APT::Install-Recommends \"false\";")
                // Translated descriptions are several MB of download that nothing here
                // ever reads.
                appendLine("Acquire::Languages \"none\";")
            },
        )
        write(
            File(root, "etc/dpkg/dpkg.cfg.d/01-kern"),
            buildString {
                // dpkg fsyncs after every extracted file, which on phone storage is the
                // single largest cost of installing anything. Container images disable it
                // for exactly this reason. The exposure is a half-written install if the
                // device loses power mid-apt, and Repair already recovers from that.
                appendLine("force-unsafe-io")
                // Nothing on a phone reads man pages or package docs, and skipping them
                // saves both time and a surprising amount of space.
                appendLine("path-exclude=/usr/share/doc/*")
                appendLine("path-exclude=/usr/share/man/*")
                appendLine("path-exclude=/usr/share/info/*")
                appendLine("path-exclude=/usr/share/groff/*")
            },
        )
        write(
            File(root, "etc/environment"),
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\nLANG=C.UTF-8\n",
        )
        File(root, "root/projects").mkdirs()
        File(root, "tmp").mkdirs()
    }

    private fun write(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }.onFailure { Log.w(TAG, "could not write ${file.absolutePath}: ${it.message}") }
    }
}
