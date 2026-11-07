package dev.eclipse.ssh.archive

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The extraction safety rules, which are the one part of View Archive where being wrong means
 * writing outside the folder the user picked.
 *
 * Every refusal here is a named attack or a named platform accident, not a style opinion: Zip
 * Slip (`..`), absolute paths, Windows drive letters, reserved device names, and the control
 * characters that filesystem APIs treat as terminators. Each gets its own test so a future
 * relaxation has to argue with the specific case, not with a blob of "weird names".
 */
class SafeArchivePathTest {
    @Test
    fun `an ordinary relative path passes through unchanged`() {
        assertThat(SafeArchivePath.safeDestinationName("app/src/main.js")).isEqualTo("app/src/main.js")
    }

    @Test
    fun `a single file name passes through unchanged`() {
        assertThat(SafeArchivePath.safeDestinationName("package.json")).isEqualTo("package.json")
    }

    @Test
    fun `dotdot segments are refused wherever they sit`() {
        // The classic Zip Slip shape, at every depth the attack actually uses.
        assertThat(SafeArchivePath.safeDestinationName("../../etc/passwd")).isNull()
        assertThat(SafeArchivePath.safeDestinationName("a/../../etc/passwd")).isNull()
        assertThat(SafeArchivePath.safeDestinationName("a/b/../c")).isNull()
        assertThat(SafeArchivePath.safeDestinationName("..")).isNull()
    }

    @Test
    fun `absolute paths are refused in both unix and windows spelling`() {
        assertThat(SafeArchivePath.safeDestinationName("/etc/passwd")).isNull()
        assertThat(SafeArchivePath.safeDestinationName("C:/Windows/system32")).isNull()
        assertThat(SafeArchivePath.safeDestinationName("c:/whatever")).isNull()
    }

    @Test
    fun `empty and punctuation-only paths are refused`() {
        assertThat(SafeArchivePath.safeDestinationName("")).isNull()
        assertThat(SafeArchivePath.safeDestinationName(".")).isNull()
        assertThat(SafeArchivePath.safeDestinationName("./")).isNull()
        assertThat(SafeArchivePath.safeDestinationName("//")).isNull()
    }

    @Test
    fun `nul and newline inside a name are refused`() {
        // These bytes terminate the name at the C API layer; the file that would be created is
        // not the file the listing showed.
        assertThat(SafeArchivePath.safeDestinationName("evil\u0000name/ok.txt")).isNull()
        assertThat(SafeArchivePath.safeDestinationName("evil\nname/ok.txt")).isNull()
    }

    @Test
    fun `windows reserved device names are refused with or without extension`() {
        assertThat(SafeArchivePath.safeDestinationName("CON")).isNull()
        assertThat(SafeArchivePath.safeDestinationName("aux.txt")).isNull()
        assertThat(SafeArchivePath.safeDestinationName("drivers/LPT1")).isNull()
        // Case-insensitive, as the Windows API is.
        assertThat(SafeArchivePath.safeDestinationName("nul.log")).isNull()
        // A name that merely STARTS with a reserved stem is fine - only the stem before the
        // first dot is the device name.
        assertThat(SafeArchivePath.safeDestinationName("console.txt")).isNotNull()
    }

    @Test
    fun `unicode and long segment names are fine`() {
        assertThat(SafeArchivePath.safeDestinationName("данные/файл.txt")).isNotNull()
        assertThat(SafeArchivePath.safeDestinationName("a".repeat(200) + ".txt")).isNotNull()
    }
}

/**
 * The lazy folder tree over a flat listing.
 *
 * The contracts worth pinning are the ones the UI depends on for correctness rather than
 * convenience: implied folders appear in their parents' listings, a real directory entry wins
 * over the placeholder for the same path, children are directories-first, search is
 * case-insensitive and capped, and a folder's subtree is exactly the extract set.
 */
class ArchiveTreeTest {
    private fun file(
        path: String,
        size: Long = 10L,
    ) = ArchiveEntry(
        path = path,
        isDirectory = false,
        size = size,
        compressedSize = size / 2,
        modifiedEpochMillis = 1_000L,
        method = 8,
        dataOffset = 123L,
        encrypted = false,
    )

    private fun dir(path: String) =
        ArchiveEntry(
            path = path,
            isDirectory = true,
            size = 0L,
            compressedSize = null,
            modifiedEpochMillis = null,
            method = null,
            dataOffset = null,
            encrypted = false,
        )

    private val tree =
        ArchiveTree(
            listOf(
                file("package.json"),
                file("README.md"),
                dir("src/"),
                file("src/main.js"),
                file("src/util/helper.js"),
                // "docs/guide/intro.md" implies both "docs" and "docs/guide" with no dir entries.
                file("docs/guide/intro.md"),
            ),
        )

    @Test
    fun `root children list real and implied folders first, files after`() {
        val names = tree.rootChildren().map { it.path }
        // "src" from its real entry, "docs" implied by the nested file; then the root files.
        assertThat(names).containsExactly("src", "docs", "package.json", "README.md").inOrder()
    }

    @Test
    fun `a folder the archive never listed appears with unknown metadata`() {
        val docs = tree.entryAt("docs")!!
        assertThat(docs.isDirectory).isTrue()
        // Unknown, not invented: the placeholder claims no size or mtime.
        assertThat(docs.size).isEqualTo(0L)
        assertThat(docs.modifiedEpochMillis).isNull()
    }

    @Test
    fun `nested implied folders cascade all the way down`() {
        assertThat(tree.children("docs/guide")!!.map { it.path }).containsExactly("docs/guide/intro.md")
        assertThat(tree.hasChildren("docs/guide/intro.md")).isFalse()
    }

    @Test
    fun `an empty folder still lists as empty, not as missing`() {
        val empty = ArchiveTree(listOf(dir("empty/")))
        assertThat(empty.children("empty")).isEmpty()
        // Missing is null; empty is a list - the UI distinguishes "nothing here" from "not a folder".
        assertThat(empty.children("nope")).isNull()
    }

    @Test
    fun `children are directories first, then files, each in listing order`() {
        val names = tree.children("src")!!.map { it.path }
        assertThat(names).containsExactly("src/util", "src/main.js").inOrder()
        // "src/util" is implied by src/util/helper.js and lists before the file that implies it
        // is NOT the rule - listing order of the PARENT's slice is what governs, and util's
        // placeholder was appended when helper.js was indexed.
    }

    @Test
    fun `a real directory entry wins over the implied placeholder`() {
        val real = ArchiveTree(listOf(dir("a/"), file("a/b.txt")))
        val a = real.entryAt("a")!!
        // The real entry was in the listing; it keeps its identity rather than being replaced.
        assertThat(a).isEqualTo(dir("a/").copy(path = "a"))
        assertThat(a.isDirectory).isTrue()
    }

    @Test
    fun `search matches name, directory and full path case-insensitively`() {
        val byName = tree.search("package")
        assertThat(byName.map { it.path }).containsExactly("package.json")

        val byDir = tree.search("docs")
        assertThat(byDir.map { it.path }).contains("docs")
        assertThat(byDir.map { it.path }).contains("docs/guide/intro.md")

        val byFullPath = tree.search("UTIL/HELPER")
        assertThat(byFullPath.map { it.path }).containsExactly("src/util/helper.js")
    }

    @Test
    fun `search is capped and blank queries return nothing`() {
        val many = (1..1000).map { file("f$it.txt") }
        val big = ArchiveTree(many)
        assertThat(big.search("f", limit = 500)).hasSize(500)
        assertThat(big.search("")).isEmpty()
        assertThat(big.search("   ")).isEmpty()
    }

    @Test
    fun `ancestors walk from the root down to the parent`() {
        assertThat(tree.ancestorsOf("src/util/helper.js")).containsExactly("src", "src/util").inOrder()
        assertThat(tree.ancestorsOf("package.json")).isEmpty()
    }

    @Test
    fun `a folder's subtree is exactly the selective-extract set`() {
        val paths = tree.subtreeOf("src").map { it.path }
        assertThat(paths).containsExactly("src", "src/main.js", "src/util", "src/util/helper.js")
        // The root's subtree is everything.
        assertThat(tree.subtreeOf("").size).isEqualTo(tree.entryCount)
    }
}
