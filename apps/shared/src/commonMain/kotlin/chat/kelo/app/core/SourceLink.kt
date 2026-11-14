package chat.kelo.app.core

/**
 * The link from the version line in My to the exact source of the build, on GitHub (docs/versioning.md). The build says which commit
 * the app's source came from ([commit], 40 lowercase hex) and whether tracked files under the app's inputs differed from it
 * ([dirty]); an app built from a source tarball has no commit (""). The iPhone app has the same rule (KeloCore SourceLink.swift),
 * with the same test vectors.
 */
object SourceLink {
    const val REPO = "https://github.com/kelo-chat/kelo-src"

    private val FULL = Regex("^[0-9a-f]{40}$")

    /** A page of this commit, only for a clean build of a full lowercase sha; null otherwise (a modified build has no page to show). */
    fun url(commit: String, dirty: Boolean): String? = if (!dirty && FULL.matches(commit)) "$REPO/tree/$commit" else null

    /** The first seven hex of a valid commit; null when the build did not say (empty, short, uppercase or not hex). */
    fun short(commit: String): String? = if (FULL.matches(commit)) commit.substring(0, 7) else null

    /** What the version line shows: "a1b2c3d", or "a1b2c3d (modified)" when dirty; [modified] is the app's word for it (my_source_modified). */
    fun label(commit: String, dirty: Boolean, modified: String): String? =
        short(commit)?.let { if (dirty) "$it ($modified)" else it }
}
