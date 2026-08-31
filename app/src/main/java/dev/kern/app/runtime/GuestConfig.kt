package dev.kern.app.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.io.File
import java.net.Inet4Address

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

    /**
     * Public resolvers, used only after the device's own have been tried.
     *
     * They cannot be the whole answer. A guest that always asks Cloudflare cannot resolve
     * anything on a network whose names only its own resolver knows - a corporate or home
     * network with internal hosts - and it is refused outright behind a captive portal or
     * on a network that blocks outbound 53 to anywhere else, which is common on hotel and
     * campus Wi-Fi. `apt` then fails in a way that reads as a dead mirror. They are still
     * worth keeping underneath, because a device resolver captured at setup time is stale
     * the moment the phone changes network.
     */
    private val PUBLIC_RESOLVERS = listOf("1.1.1.1", "8.8.8.8")

    /**
     * What `/etc/resolv.conf` should say right now: the device's resolvers first, then
     * [PUBLIC_RESOLVERS] as a floor.
     *
     * glibc tries each in turn, so listing both means the guest prefers the network's own
     * view of DNS and still resolves when that view is missing or broken. Capped at
     * resolv.conf's own limit - glibc reads three nameservers and silently ignores the
     * rest, so a long list would push the fallbacks past where they can ever be reached.
     *
     * internal so the ordering and the cap can be tested without a device.
     */
    internal fun resolvConf(context: Context): String =
        (deviceResolvers(context) + PUBLIC_RESOLVERS)
            .distinct()
            .take(MAX_NAMESERVERS)
            .joinToString("") { "nameserver $it\n" }

    /** glibc's `MAXNS`. Anything past the third line is never consulted. */
    private const val MAX_NAMESERVERS = 3

    /**
     * The resolvers Android is using for the active network, or nothing if it will not say.
     *
     * IPv4 only, deliberately: PRoot does not stop the guest reaching an IPv6 resolver, but
     * a phone on a network without working IPv6 routing would then spend the resolver
     * timeout on every lookup before falling through, which turns a slow `apt` into one
     * that looks hung. The v4 addresses in the same list do the job.
     */
    private fun deviceResolvers(context: Context): List<String> = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = manager?.activeNetwork ?: return@runCatching emptyList()
        manager.getLinkProperties(active)?.dnsServers.orEmpty()
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    }.onFailure {
        Log.w(TAG, "could not read the device's DNS servers: ${it.message}")
    }.getOrDefault(emptyList())

    /**
     * Rewrite `/etc/resolv.conf` for the network the phone is on now.
     *
     * [apply] runs at setup and Repair only, so without this the guest keeps whatever
     * resolvers were current when it was installed - which on a phone is wrong within the
     * day. Called before anything that has to reach the network, and cheap enough to not
     * be worth deciding about: two syscalls and a 60-byte write.
     */
    fun refreshDns(context: Context) {
        if (!LinuxRuntime.isInstalled(context)) return
        write(File(LinuxRuntime.rootfsDir(context), "etc/resolv.conf"), resolvConf(context))
    }

    fun apply(context: Context) {
        val root = LinuxRuntime.rootfsDir(context)

        write(File(root, "etc/resolv.conf"), resolvConf(context))
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
