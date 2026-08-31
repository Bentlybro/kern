package dev.kern.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether an update is offered at all, and whether the APK behind it can be checked.
 *
 * `isNewer` has been `internal` since it was written — widened to be reachable from a test
 * that did not exist. It decides, on every launch, whether the user is shown an update:
 * get it backwards and the app either offers a downgrade or never offers anything again,
 * and both are invisible until someone notices they are several versions behind.
 */
class UpdateVersionTest {

    // ---- which version is newer ---------------------------------------------

    @Test
    fun `a later version is newer`() {
        assertTrue(Updates.isNewer("0.1.4", "0.1.3"))
        assertTrue(Updates.isNewer("0.2.0", "0.1.9"))
        assertTrue(Updates.isNewer("1.0.0", "0.9.9"))
    }

    @Test
    fun `the same version is not newer than itself`() {
        // The whole update check hangs off this. If it were true, the app would offer to
        // install the version it is already running, every time it was opened.
        assertFalse(Updates.isNewer("0.1.3", "0.1.3"))
    }

    @Test
    fun `an older version is never offered`() {
        assertFalse(Updates.isNewer("0.1.2", "0.1.3"))
        assertFalse(Updates.isNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun `versions compare number by number, not as strings`() {
        // 0.10.0 sorts *before* 0.9.0 as text, which would strand everyone on 0.9.x
        // permanently the first time the minor version reached double figures.
        assertTrue(Updates.isNewer("0.10.0", "0.9.0"))
        assertFalse(Updates.isNewer("0.9.0", "0.10.0"))
        assertTrue(Updates.isNewer("0.1.10", "0.1.9"))
    }

    @Test
    fun `a missing segment counts as zero`() {
        // The release workflow names the tag from appVersionName, and nothing forces that
        // to have three parts.
        assertFalse(Updates.isNewer("0.1", "0.1.0"))
        assertFalse(Updates.isNewer("0.1.0", "0.1"))
        assertTrue(Updates.isNewer("0.2", "0.1.9"))
    }

    @Test
    fun `a release beats a pre-release of the same number`() {
        // Someone running 0.1.4-rc1 should be moved onto 0.1.4 when it ships, and someone
        // on 0.1.4 must never be offered its own release candidate.
        assertTrue(Updates.isNewer("0.1.4", "0.1.4-rc1"))
        assertFalse(Updates.isNewer("0.1.4-rc1", "0.1.4"))
    }

    @Test
    fun `a tag that is not a version does not produce an update`() {
        // fetchLatest strips a leading "v" and nothing else, so whatever else a tag might
        // be reaches this. The safe direction is "no update", never "downgrade".
        for (tag in listOf("latest", "nightly", "kern-0.1.9", "")) {
            assertFalse("offered an update for tag <$tag>", Updates.isNewer(tag, "0.1.3"))
        }
    }

    @Test
    fun `the comparison is a strict ordering, not a coin flip`() {
        // Both directions asserted over the same pairs: a comparison that answered true to
        // both would offer an update in perpetuity, alternating between two versions.
        val ascending = listOf("0.0.1", "0.1.0", "0.1.3", "0.1.10", "0.2.0", "1.0.0", "1.0.1")
        for (i in ascending.indices) {
            for (j in ascending.indices) {
                assertEquals(
                    "${ascending[i]} vs ${ascending[j]}",
                    i > j,
                    Updates.isNewer(ascending[i], ascending[j]),
                )
            }
        }
    }

    // ---- the asset digest ---------------------------------------------------

    @Test
    fun `github's digest is read as the hex download expects`() {
        // The API reports `sha256:<hex>`; download() wants the hex on its own, lowercase.
        val hex = "b2b46a37324ea1954e93f293fe6d7c2241daf2fc298c4022e6e4caceeed74cab"
        assertEquals(hex, Updates.digestOf("sha256:$hex"))
        assertEquals(hex, Updates.digestOf("sha256:${hex.uppercase()}"))
        assertEquals(hex, Updates.digestOf("  sha256:$hex  "))
    }

    @Test
    fun `a digest that cannot be checked is null rather than a guess`() {
        // A release published before GitHub reported digests returns an empty string here,
        // and a future algorithm would return something this cannot verify. Both have to
        // be indistinguishable from "none offered" - a wrong digest would reject an APK
        // that is perfectly good, and a truncated one would check almost nothing.
        val hex = "b2b46a37324ea1954e93f293fe6d7c2241daf2fc298c4022e6e4caceeed74cab"
        assertNull(Updates.digestOf(null))
        assertNull(Updates.digestOf(""))
        assertNull(Updates.digestOf("   "))
        assertNull("a bare hex string is not a labelled digest", Updates.digestOf(hex))
        assertNull("another algorithm was accepted as sha256", Updates.digestOf("sha512:$hex"))
        assertNull("a truncated digest was accepted", Updates.digestOf("sha256:" + hex.take(32)))
        assertNull("a non-hex digest was accepted", Updates.digestOf("sha256:" + "z".repeat(64)))
    }

    @Test
    fun `a release carries no digest unless one was reported`() {
        // The default matters: it is what download() reads as "nothing to check against",
        // and it is what makes Updates.download clear any stale .part rather than resume
        // into a file it cannot verify.
        val release = Updates.Release(
            version = "0.1.4",
            notes = "",
            apkUrl = "https://github.com/${Updates.REPO}/releases/download/v0.1.4/kern.apk",
            sizeBytes = 1,
        )
        assertNull(release.sha256)
    }
}
