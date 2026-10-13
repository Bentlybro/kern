package dev.kern.app.guest

import android.util.Log
import dev.kern.app.core.fetchText
import kotlinx.coroutines.delay

/**
 * Which Ubuntu base image to install, and the digest it must match.
 *
 * Split from [RootfsInstaller], which does the installing: this is only the question of
 * what to fetch, asked of cdimage's SHA256SUMS with a pinned, verified fallback. It is
 * pure apart from the one network read, which is what lets the choice be unit tested.
 */
internal object RootfsCatalog {

    private const val TAG = "Kern"

    private const val CDIMAGE_BASE =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/${GuestConfig.UBUNTU_RELEASE}/release/"

    /**
     * The name cdimage serves today, used only when SHA256SUMS cannot be read.
     *
     * Canonical prunes this plain name once point releases ship: 24.04's is already a 404
     * and its SHA256SUMS lists only 24.04.3 and 24.04.4. 26.04 will follow, and a URL
     * built from the release alone is therefore a bomb with Canonical's finger on the
     * timer - every new install dying at step one, fixable only by shipping an app.
     */
    private const val ROOTFS_FALLBACK_NAME =
        "ubuntu-base-${GuestConfig.UBUNTU_RELEASE}-base-arm64.tar.gz"

    /**
     * The digest of that exact file, so the fallback path is still verified.
     *
     * This does not reintroduce the rot the comment above warns about, because it pins the
     * digest of a *name that is already pinned*: the day Canonical prunes
     * [ROOTFS_FALLBACK_NAME], this URL 404s and the fallback is dead whether or not a hash
     * sits beside it. The two rot together and always did.
     *
     * What it buys is that "SHA256SUMS was unreachable" stops being a way to turn a
     * verified install into an unverified one. That mattered: anything able to drop a
     * single request - a captive portal, a middlebox, a hostile network - could force the
     * downgrade, and 400 MB then went in unchecked with only a log line to say so.
     */
    internal const val ROOTFS_FALLBACK_SHA256 =
        "b2b46a37324ea1954e93f293fe6d7c2241daf2fc298c4022e6e4caceeed74cab"

    /** A dropped request should not decide this; a mirror that is genuinely gone may. */
    private const val SUMS_ATTEMPTS = 3

    /**
     * Which base image to fetch, and what it should hash to, asked of cdimage rather than
     * assumed.
     *
     * Deliberately not driven by a compile-time hash constant. Pinning the digest of
     * whatever we fetch would turn Canonical's routine rename into a checksum mismatch -
     * Kern accusing itself of shipping a tampered mirror, on Canonical's schedule, fixable
     * only by shipping a new app. SHA256SUMS is one fetch answering both questions at
     * once, and it cannot rot that way.
     *
     * When it is unreachable, [ROOTFS_FALLBACK_NAME] still gets a try - and it is verified
     * too, against [ROOTFS_FALLBACK_SHA256]. A cdimage restructure should cost neither the
     * install nor the verification, and there is never a path here that downloads
     * unchecked.
     */
    suspend fun resolveRootfs(): RootfsSource = selectRootfs(fetchSums())

    /**
     * SHA256SUMS, retried before it is given up on.
     *
     * One attempt made a single dropped request enough to change which file we fetch and,
     * before the fallback carried a digest, whether it was checked at all. A phone loses
     * its radio for a second routinely, so that was not a rare path.
     */
    private suspend fun fetchSums(): String? {
        repeat(SUMS_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(2_000L * attempt)
            runCatching { fetchText(CDIMAGE_BASE + "SHA256SUMS") }
                .onSuccess { return it }
                .onFailure { Log.w(TAG, "could not read SHA256SUMS (attempt ${attempt + 1})", it) }
        }
        return null
    }

    /**
     * The newest ubuntu-base of our own series named in [sums], with the digest listed
     * beside it - or today's literal name and no digest when [sums] is unreadable or names
     * nothing we can use.
     *
     * Split from the fetch above, and internal rather than private, so the choice can be
     * tested against real SHA256SUMS text without reaching cdimage.
     */
    internal fun selectRootfs(sums: String?): RootfsSource {
        val fallback = RootfsSource(
            ROOTFS_FALLBACK_NAME,
            CDIMAGE_BASE + ROOTFS_FALLBACK_NAME,
            ROOTFS_FALLBACK_SHA256,
            fromSums = false,
        )
        if (sums == null) return fallback

        var best: RootfsSource? = null
        var bestVersion = emptyList<Int>()
        for (line in sums.lineSequence()) {
            val match = ROOTFS_LINE.matchEntire(line.trim()) ?: continue
            val (digest, name, version) = match.destructured
            // Point releases of our own series only. 26.04.1 supersedes 26.04, but 26.10
            // is a different Ubuntu and the codename GuestConfig writes into sources.list
            // would no longer describe it.
            val ours = version == GuestConfig.UBUNTU_RELEASE ||
                version.startsWith("${GuestConfig.UBUNTU_RELEASE}.")
            if (!ours) continue
            val parts = version.split('.').map { it.toIntOrNull() ?: 0 }
            if (best == null || newer(parts, bestVersion)) {
                best = RootfsSource(
                    name,
                    CDIMAGE_BASE + name,
                    digest.lowercase(),
                    fromSums = true,
                )
                bestVersion = parts
            }
        }
        return best ?: fallback
    }

    /**
     * internal, not private, only so [selectRootfs] can be read back in a unit test.
     *
     * [sha256] is non-null by construction: there is no longer a route through this file
     * that hands [download] an image with nothing to check it against. [fromSums] says
     * only where the digest came from, which setup reports and nothing else acts on.
     */
    internal class RootfsSource(
        val name: String,
        val url: String,
        val sha256: String,
        val fromSums: Boolean,
    )

    /** `<digest> *ubuntu-base-26.04-base-arm64.tar.gz`, with the point release captured. */
    private val ROOTFS_LINE =
        Regex("""([0-9a-fA-F]{64})\s+\*?(ubuntu-base-([0-9.]+)-base-arm64\.tar\.gz)""")

    /** Number by number, because 26.04.10 is newer than 26.04.9 and sorts before it. */
    private fun newer(candidate: List<Int>, incumbent: List<Int>): Boolean {
        for (i in 0 until maxOf(candidate.size, incumbent.size)) {
            val left = candidate.getOrElse(i) { 0 }
            val right = incumbent.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }
}
