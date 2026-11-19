package io.github.sumankayal247.gbaemulator.data.rom

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Files

/**
 * Bounded, cancellable filesystem traversal for the optional device-wide ROM scan (ADR-0006).
 * Pure Kotlin/JVM so traversal limits and filtering can be unit-tested without an Android device.
 */
object DeviceRomWalker {
    const val MAX_DEPTH = 4
    const val MAX_FILES = 500

    private val SUPPORTED_EXTENSIONS = setOf("gba", "gbc", "gb")
    private val SKIPPED_DIRECTORIES = setOf(
        "android", // other apps' private data
        "obb",
        "cache",
        "lost+found",
        "dev",
        "proc",
        "sys",
    )

    enum class CandidateOutcome { INDEXED, DUPLICATE, SKIPPED }

    data class Report(
        val inspectedFiles: Int,
        val discoveredGames: Int,
        val duplicateGames: Int,
        val skippedFiles: Int,
        val warnings: List<String>,
    )

    suspend fun scan(
        roots: List<File>,
        inspect: suspend (File) -> CandidateOutcome,
    ): Report {
        val visitedDirectories = HashSet<String>()
        val warnings = mutableListOf<String>()
        var inspectedFiles = 0
        var discoveredGames = 0
        var duplicateGames = 0
        var skippedFiles = 0

        suspend fun walk(directory: File, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (depth > MAX_DEPTH) return

            val canonical = runCatching { directory.canonicalPath }.getOrNull() ?: return
            if (!visitedDirectories.add(canonical)) return

            val children = runCatching { directory.listFiles() }.getOrNull()
            if (children == null) {
                warnings.add("Skipped unreadable folder: ${directory.name}")
                return
            }

            for (child in children) {
                currentCoroutineContext().ensureActive()
                if (inspectedFiles >= MAX_FILES) {
                    warnings.add("Stopped after $MAX_FILES ROM candidates; narrow the folder or add it with SAF.")
                    return
                }

                // Symlinks can escape the granted roots or create loops; never follow them here.
                if (runCatching { Files.isSymbolicLink(child.toPath()) }.getOrDefault(false)) continue

                if (child.isDirectory) {
                    val name = child.name.lowercase()
                    if (name.startsWith(".") || name in SKIPPED_DIRECTORIES) continue
                    walk(child, depth + 1)
                } else if (child.isFile) {
                    val extension = child.name.substringAfterLast('.', "").lowercase()
                    if (extension !in SUPPORTED_EXTENSIONS) continue
                    inspectedFiles++
                    when (inspect(child)) {
                        CandidateOutcome.INDEXED -> discoveredGames++
                        CandidateOutcome.DUPLICATE -> duplicateGames++
                        CandidateOutcome.SKIPPED -> skippedFiles++
                    }
                }
            }
        }

        for (root in roots) {
            currentCoroutineContext().ensureActive()
            walk(root, 0)
        }

        return Report(
            inspectedFiles = inspectedFiles,
            discoveredGames = discoveredGames,
            duplicateGames = duplicateGames,
            skippedFiles = skippedFiles,
            warnings = warnings.distinct(),
        )
    }
}
