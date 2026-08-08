package dev.kern.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The health check probes for *binaries* - it runs `command -v node` - but the Install
 * button it offers hands its list straight to apt, which only knows *packages*. Where the
 * two names differ, an unmapped list produces "E: Unable to locate package node": a button
 * that appears to offer a fix and cannot do anything but fail.
 *
 * The mapping is three entries long, which is exactly why it is worth pinning. It is the
 * kind of table that gets extended by hand months later, on a laptop, without a phone to
 * hand, and nothing else in the app would notice a typo in it until someone ran out of
 * disk space in Ubuntu and tapped Install.
 */
class PackagesForTest {

    @Test
    fun `node is installed from the package apt actually calls it`() {
        // Ubuntu has no package named "node" - the name belongs to an unrelated one in
        // some Debian releases, which is worse than nothing.
        assertEquals(listOf("nodejs"), HealthCheck.packagesFor(listOf("node")))
    }

    @Test
    fun `rg is installed from ripgrep`() {
        assertEquals(listOf("ripgrep"), HealthCheck.packagesFor(listOf("rg")))
    }

    @Test
    fun `installing gh also installs the trust store it cannot work without`() {
        // gh's Go TLS stack carries no root certificates of its own. Without
        // ca-certificates it rejects github.com outright, so a gh installed from this
        // button would sign in, report success, and then fail on the user's first push -
        // a failure that surfaces long after the action that caused it.
        val packages = HealthCheck.packagesFor(listOf("gh"))
        assertTrue("gh itself must still be installed: $packages", "gh" in packages)
        assertTrue("gh without a trust store cannot reach github.com", "ca-certificates" in packages)
    }

    @Test
    fun `a binary whose package shares its name is installed under that name`() {
        // The fallback carries most of the list; a mapping that only returned its known
        // entries would silently drop everything else out of the install.
        val binaries = listOf("git", "tmux", "python3")
        assertEquals(binaries, HealthCheck.packagesFor(binaries))
    }

    @Test
    fun `apt is asked for each package once, however the missing list arrived`() {
        // Two probes can want the same package - ca-certificates is both a dependency of
        // gh and a thing worth probing for on its own - and the resulting list is shown to
        // the user before it is run, where a repeat reads as a bug.
        assertEquals(
            listOf("gh", "ca-certificates"),
            HealthCheck.packagesFor(listOf("gh", "ca-certificates", "gh")),
        )
    }

    @Test
    fun `expanding one binary in the middle of a list leaves the rest where it was`() {
        // The real argument the GitHub item's Install button is built from
        // (GitHubAuth.REQUIRED_TOOLS, when the guest has none of them yet). gh grows into
        // two entries here without disturbing what surrounds it - collecting the mapped
        // names separately, or through a Set, would shuffle the list the user is shown.
        assertEquals(
            listOf("git", "gh", "ca-certificates", "tmux"),
            HealthCheck.packagesFor(listOf("git", "gh", "tmux")),
        )
    }

    @Test
    fun `nothing missing asks apt for nothing`() {
        // An Install button with an empty package list runs `apt install` with no
        // arguments, which succeeds without installing anything and looks like a fix.
        assertEquals(emptyList<String>(), HealthCheck.packagesFor(emptyList()))
    }
}
