package dev.kern.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which Ubuntu image a fresh install downloads, decided from cdimage's SHA256SUMS.
 *
 * This is step one of setup and there is no way past it: pick a name Canonical has since
 * pruned and every new install dies at the first download, fixable only by shipping an app.
 * Pick a file from the wrong series and the guest comes up with apt sources written for a
 * codename that no longer describes it. Pair a name with the wrong digest and every
 * download is rejected as corrupt.
 *
 * The fixtures are built from [GuestConfig.UBUNTU_RELEASE] rather than written as "26.04",
 * so they keep describing the behaviour after the release is bumped - a case pinned to a
 * series the app no longer targets tests nothing.
 */
class RootfsResolveTest {

    @Test
    fun `today's SHA256SUMS names only the plain release and that is the image fetched`() {
        // The real document for a series before its first point release: one tarball per
        // architecture and no version suffix anywhere.
        val document = sums(
            image(RELEASE, "amd64"),
            image(RELEASE, "arm64"),
            image(RELEASE, "armhf"),
            image(RELEASE, "ppc64el"),
            image(RELEASE, "riscv64"),
            image(RELEASE, "s390x"),
        )
        assertEquals(image(RELEASE), resolve(document).name)
    }

    @Test
    fun `once Canonical prunes the plain name the newest point release is used instead`() {
        // What 24.04's SHA256SUMS looks like today, and what this series becomes: the plain
        // name is a 404 and only point releases are listed. This is the whole reason the
        // name is asked for rather than assembled from a constant.
        val document = sums(image("$RELEASE.1"), image("$RELEASE.2"))
        assertEquals(image("$RELEASE.2"), resolve(document).name)
    }

    @Test
    fun `a tenth point release is newer than a ninth, which sorting the names would deny`() {
        // Where string ordering and version ordering disagree: "$RELEASE.9" sorts above
        // "$RELEASE.10" as text, so a resolver comparing names spends the rest of the
        // series installing a superseded image and never notices.
        val document = sums(image(RELEASE), image("$RELEASE.9"), image("$RELEASE.10"))
        assertEquals(image("$RELEASE.10"), resolve(document).name)
    }

    @Test
    fun `the newest point release wins whatever order the document lists them in`() {
        // cdimage's order is not promised, and a resolver that kept the first or the last
        // match passes one arrangement and fails another.
        val names = listOf(
            image("$RELEASE.10"),
            image(RELEASE),
            image("$RELEASE.9"),
            image("$RELEASE.1"),
        )
        for (order in listOf(names, names.reversed(), names.sorted())) {
            val document = sums(*order.toTypedArray())
            assertEquals("listed as $order", image("$RELEASE.10"), resolve(document).name)
        }
    }

    @Test
    fun `an image for another architecture is never chosen however new it is`() {
        // Architectures do not always ship the same point release, and the app has exactly
        // one: an amd64 tarball unpacks perfectly and then nothing inside the guest runs.
        val document = sums(image("$RELEASE.5", "amd64"), image("$RELEASE.2", "arm64"))
        assertEquals(image("$RELEASE.2"), resolve(document).name)
    }

    @Test
    fun `a newer series is never chosen because the codename would stop describing it`() {
        // GuestConfig writes RELEASE's codename into sources.list. Unpacking the next
        // interim release under it gives a guest pointed at a different Ubuntu's archive.
        val document = sums(image(RELEASE), image("$NEXT_SERIES.1"))
        assertEquals(image(RELEASE), resolve(document).name)
    }

    @Test
    fun `a document offering only another series falls back rather than installing it`() {
        // The directory is per-series, so this only happens if cdimage is restructured -
        // and the right answer then is the name we know, unverified, not a stranger.
        val source = resolve(sums(image("$NEXT_SERIES.1"), image("$NEXT_SERIES.2")))
        assertEquals(image(RELEASE), source.name)
        assertNull("the fallback must not borrow another series' digest", source.sha256)
    }

    @Test
    fun `a version that merely starts with ours belongs to some other series`() {
        // The dot in the prefix check is load-bearing: without it "$RELEASE" also prefixes
        // "${RELEASE}1", which compares as a much later version and would win outright.
        val document = sums(image(RELEASE), image("${RELEASE}1"))
        assertEquals(image(RELEASE), resolve(document).name)
    }

    @Test
    fun `the digest returned is the one listed beside the image that was chosen`() {
        // download() is handed the URL and the digest separately and checks one against the
        // other. A name paired with a neighbour's digest is not a wrong image - it is every
        // install failing as "corrupted in transit", with nothing the user can do about it.
        val chosen = image("$RELEASE.2")
        val document = sums(image("$RELEASE.1"), chosen, image("$RELEASE.2", "amd64"))
        val source = RootfsInstaller.selectRootfs(document)
        assertEquals(chosen, source.name)
        assertEquals(digestFor(chosen), source.sha256)
    }

    @Test
    fun `an unreachable SHA256SUMS still gets today's name, unverified`() {
        // A cdimage outage or restructure should cost verification, not the install. The
        // null is what makes setup say out loud that this one could not be checked.
        val source = resolve(null)
        assertEquals(image(RELEASE), source.name)
        assertNull(source.sha256)
    }

    @Test
    fun `an empty or unparseable document falls back instead of throwing`() {
        // fetchText() returns whatever arrived with a 200, which on a misconfigured mirror
        // or a captive portal is an HTML page. Setup's first step must not be an exception.
        val garbage = listOf(
            "",
            "\n\n   \n",
            "<html><head><title>404 Not Found</title></head></html>",
            // A digest one character short of a SHA-256, and a body cut off mid-name.
            "0".repeat(63) + " *" + image(RELEASE),
            "0".repeat(64) + " *ubuntu-base-",
            // A real-looking line whose version is not a version.
            "0".repeat(64) + " *ubuntu-base-devel-base-arm64.tar.gz",
        )
        for (document in garbage) {
            // Not resolve(): its digest cross-check would read the near-miss lines above as
            // if they had been accepted.
            val source = RootfsInstaller.selectRootfs(document)
            assertEquals("accepted <$document>", image(RELEASE), source.name)
            assertNull("accepted <$document>", source.sha256)
        }
    }

    @Test
    fun `both forms sha256sum writes are accepted`() {
        // cdimage serves the binary-mode asterisk today, but the two spellings differ only
        // by a character nobody would think to preserve.
        val name = image("$RELEASE.1")
        for (document in listOf("${digestFor(name)} *$name", "${digestFor(name)}  $name")) {
            assertEquals(name, RootfsInstaller.selectRootfs(document).name)
        }
    }

    @Test
    fun `a document with carriage returns is still read line by line`() {
        // The document arrives as bytes off a socket via any number of proxies and mirrors.
        // A stray CR left on the end of each line must not cost the whole verification.
        val name = image("$RELEASE.1")
        val document = sums(name).replace("\n", "\r\n")
        val source = RootfsInstaller.selectRootfs(document)
        assertEquals(name, source.name)
        assertEquals(digestFor(name), source.sha256)
    }

    @Test
    fun `a digest written in upper case is matched and handed on in lower`() {
        // The digest is text from a third party, and download() documents the hex it is
        // given as lowercase.
        val name = image("$RELEASE.1")
        val source = RootfsInstaller.selectRootfs("${digestFor(name).uppercase()} *$name")
        assertEquals(name, source.name)
        assertEquals(digestFor(name), source.sha256)
    }

    @Test
    fun `the fixtures give every image a digest of its own`() {
        // Without this, "the digest belongs to the image chosen" could pass by collision
        // and the rest of this file would have quietly stopped checking the pairing.
        val names = listOf(
            image(RELEASE),
            image("$RELEASE.1"),
            image("$RELEASE.2"),
            image("$RELEASE.10"),
            image(RELEASE, "amd64"),
            image("$NEXT_SERIES.1"),
        )
        val digests = names.map { digestFor(it) }
        assertEquals(names.size, digests.toSet().size)
        assertTrue(digests.all { it.matches(Regex("[0-9a-f]{64}")) })
    }

    // ---- fixtures ------------------------------------------------------------

    /** The tarball cdimage names for a version on an architecture. */
    private fun image(version: String, arch: String = "arm64") =
        "ubuntu-base-$version-base-$arch.tar.gz"

    /** A SHA256SUMS document listing [files] in cdimage's own binary-mode form. */
    private fun sums(vararg files: String): String =
        files.joinToString("\n", postfix = "\n") { "${digestFor(it)} *$it" }

    /**
     * Sixty-four hex characters derived from the file's own name, so which digest comes
     * back says which line was chosen. Not a real SHA-256 of anything: the resolver only
     * ever copies this string across.
     */
    private fun digestFor(file: String): String =
        Integer.toHexString(file.hashCode()).padStart(8, '0').repeat(8)

    /**
     * [RootfsInstaller.selectRootfs], with the two invariants every caller depends on
     * checked on the way through.
     */
    private fun resolve(document: String?): RootfsInstaller.RootfsSource {
        val source = RootfsInstaller.selectRootfs(document)
        // The URL is built from the name rather than returned with it, and only the name is
        // ever shown, so a mismatch here downloads one image while reporting another.
        assertEquals(
            "the URL does not name the chosen image",
            RELEASE_DIR + source.name,
            source.url,
        )
        val listed = document?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.endsWith(source.name) }
        if (listed != null) {
            assertEquals(
                "the digest belongs to another image",
                listed.take(64).lowercase(),
                source.sha256,
            )
        }
        return source
    }

    private companion object {
        /** The series this build of the app is for, and the only one it may install. */
        val RELEASE = GuestConfig.UBUNTU_RELEASE

        /**
         * The interim release after ours - 26.04 -> 26.10, 26.10 -> 27.04. Newer than ours
         * on purpose: a resolver that simply took the highest version it saw would sail
         * through a fixture that only offered an older series.
         */
        val NEXT_SERIES: String = run {
            val year = RELEASE.substringBefore('.').toInt()
            if (RELEASE.substringAfter('.') == "04") "$year.10" else "${year + 1}.04"
        }

        /** Where cdimage keeps our series. The trailing slash is load-bearing. */
        val RELEASE_DIR = "https://cdimage.ubuntu.com/ubuntu-base/releases/$RELEASE/release/"
    }
}
