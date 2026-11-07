package dev.eclipse.ssh.archive

/**
 * The extraction safety layer: turning an archive-internal path into a name that is safe to
 * create under a destination directory, and refusing the ones that are not.
 *
 * Archives are data from a server the user connected to, not data the app authored, and archive
 * writers have produced hostile member names in the wild - the classic is `../../../../etc/passwd`
 * in a ZIP, where a naive `File(dest, entryName)` writes outside the destination and the attack
 * is named Zip Slip. The TAR family adds a second door: an entry can be a *symlink* whose target
 * points anywhere, so a later entry writing "through" it escapes the destination even though
 * every literal path looked fine. This app's model of an archive is files and folders only
 * ([ArchiveEntry] carries no link target), so the extract layer never creates links at all - which
 * closes that door at its hinge rather than trying to chase targets.
 *
 * Pure, and free of any filesystem type, so the rules are testable without touching disk: the
 * extract layer calls [safeDestinationName] with the archive path and uses the returned relative
 * name under the destination directory it already validated.
 */
object SafeArchivePath {
    /**
     * Maps an archive-internal path to the relative file name it may be created under, or null
     * when the entry must not be extracted.
     *
     * Refused, each with the same answer (null, logged by the caller, never an exception - a
     * hostile archive must not be able to abort the extraction of the honest entries around it):
     *  - any `..` segment anywhere (the Zip Slip classic);
     *  - a path that is absolute after normalization (a leading `/`, or a Windows drive letter
     *    such as `C:` - some real archives carry Windows-absolute names);
     *  - empty or punctuation-only paths;
     *  - a name containing a NUL or a raw newline (breaks the filesystem API's own assumptions);
     *  - a name whose segment is a Windows reserved device (`CON`, `PRN`, `AUX`, `NUL`, `COM1`-`9`,
     *    `LPT1`-`9`) - harmless on Linux, but the extracted tree may be copied to FAT/NTFS media
     *    later, where such a name fails at copy time instead of extract time.
     *
     * Accepted paths are returned exactly as given, in [normalizeArchivePath]'s shape:
     * '/'-separated, relative, no `.` segments. The caller joins it onto the destination with its
     * own platform's separator; no `..` being present is what makes that join safe.
     */
    fun safeDestinationName(path: String): String? {
        if (path.isEmpty()) return null
        if ('\u0000' in path || '\n' in path) return null
        // A leading '/' survived normalization only if the caller passed a raw path; a drive
        // letter is the Windows-flavoured absolute. Both mean "not relative to the destination".
        if (path.startsWith("/")) return null
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        if (segments.any { isWindowsReserved(it) }) return null
        val first = segments.first()
        // Uppercase alone is not enough: real archives carry lowercase drive letters too, and
        // Windows resolves both spellings to the same absolute path.
        if (first.length == 2 && first[1] == ':' && first[0].uppercaseChar() in 'A'..'Z') return null
        return path
    }

    /** Case-insensitive match against the Windows reserved device names, extension optional. */
    private fun isWindowsReserved(segment: String): Boolean {
        val stem = segment.substringBefore('.').uppercase()
        return stem in RESERVED_STEMS
    }

    private val RESERVED_STEMS =
        setOf(
            "CON",
            "PRN",
            "AUX",
            "NUL",
            "COM1",
            "COM2",
            "COM3",
            "COM4",
            "COM5",
            "COM6",
            "COM7",
            "COM8",
            "COM9",
            "LPT1",
            "LPT2",
            "LPT3",
            "LPT4",
            "LPT5",
            "LPT6",
            "LPT7",
            "LPT8",
            "LPT9",
        )
}
