package com.diapilot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A COMMENT MUST DESCRIBE WHAT IT IS ATTACHED TO.
 *
 * This file exists after the user asked: "if the code goes up for review, the
 * comments need to match reality and not overload it with information, or the
 * reviewer will draw the wrong conclusions." It pins the one class of
 * mismatch that can be checked MECHANICALLY — and it turned out to be common.
 *
 * ## What exactly this catches
 *
 * Two KDoc blocks in a row, with no declaration between them. Kotlin and
 * Dokka attach ONLY the last one to the declaration; everything above it is
 * an orphan. The compiler stays silent, and a person reading top to bottom
 * attributes the first block to the declaration.
 *
 * That is exactly what happened to `Settings.activityModelV2`: THREE KDoc
 * blocks sat above it in a row, 54 lines, and only the top one described it.
 * The second one talked about "a dish's own curve outweighs the structural
 * mix", the third about learned dish curves with a "3/5/8 threshold" table.
 * Both of those settings had been removed earlier, and their documentation
 * stayed glued to the next function. A reviewer reading this file would have
 * learned about three mechanisms where only one lives.
 *
 * 44 such spots were found across 28 files, 485 lines of orphaned text. All
 * of them were sorted out: some merged, some downgraded to `//`, eight moved
 * to the declaration they actually describe (`fun forecast`,
 * `ForecastLedger.record`, `hypoAlertEnabled`, `PhysioAutoFitV1.AXES`, and others).
 *
 * ## Why this is a test, not a one-off cleanup
 *
 * Because the pattern comes back on its own: inserting a constant between a
 * doc block and its function is a one-line move, and nothing but this check
 * will say anything about it.
 *
 * ## What this test does NOT check, and that is worth saying plainly
 *
 * It cannot tell a correct comment from a stale one. Matching content to code
 * relies on the discipline of "measure before asserting" and on
 * `InsulinModelDocTest`, which pins the document's numbers against the
 * constants themselves. This is only the structural trap.
 */
class CommentContractTest {

    private fun sourceRoots(): List<File> = listOf(
        // The test runs from the module directory (:core), hence both variants.
        File("src/main/kotlin"), File("core/src/main/kotlin"),
        File("../core/src/main/kotlin"), File("../app/src/main/java"),
        File("app/src/main/java"),
    ).filter { it.isDirectory }

    private fun kotlinFiles(): List<File> =
        sourceRoots().flatMap { it.walkTopDown().filter { f -> f.extension == "kt" } }
            .distinctBy { it.canonicalPath }

    /** Bounds of each KDoc block in a file, as (first line, last line) pairs. */
    private fun kdocBlocks(lines: List<String>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].trimStart().startsWith("/**")) {
                val start = i
                while (i < lines.size && !lines[i].contains("*/")) i++
                out += start..i
            }
            i++
        }
        return out
    }

    @Test
    fun `no declaration carries a KDoc that belongs to something above it`() {
        val files = kotlinFiles()
        assertTrue("source roots not found — the test would pass vacuously", files.size > 100)

        val offenders = mutableListOf<String>()
        for (f in files) {
            val lines = f.readText().split("\n")
            val blocks = kdocBlocks(lines)
            blocks.zipWithNext { a, b ->
                val between = lines.subList(a.last + 1, b.first)
                if (between.all { it.isBlank() }) {
                    offenders += "${f.path}:${a.first + 1} — a KDoc immediately followed by " +
                        "another KDoc (:${b.first + 1}). Only the second one will attach."
                }
            }
        }
        assertEquals(
            "Stacked KDoc blocks: only the last one attaches, the rest read as its " +
                "description and lie.\n" + offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    /**
     * And the second half of the same question — volume.
     *
     * 30% of non-blank production lines are comments, and that is deliberate:
     * the file keeps a research log, because past data cannot be read without
     * knowing which model produced it. But volume has a cost for an outside
     * reader, and that cost is measurable.
     *
     * The threshold here is NOT an ideal, it is a ratchet: it is set just
     * above the current value so the block cannot quietly double in size. If
     * the test fails, check whether history is being rewritten where a single
     * fact would do.
     */
    @Test
    fun `the largest comment blocks stay within a stated budget`() {
        val files = kotlinFiles()
        var blockLines = 0
        var blocks = 0
        for (f in files) {
            val lines = f.readText().split("\n")
            var run = 0
            var inBlock = false
            for (ln in lines) {
                val s = ln.trim()
                val isComment = when {
                    inBlock -> { if (s.contains("*/")) inBlock = false; true }
                    s.startsWith("/*") -> { if (!s.contains("*/")) inBlock = true; true }
                    s.startsWith("//") -> true
                    else -> false
                }
                if (isComment) run++ else { if (run >= 25) { blocks++; blockLines += run }; run = 0 }
            }
            if (run >= 25) { blocks++; blockLines += run }
        }
        assertTrue(
            "блоков комментария >=25 строк: $blocks на $blockLines строк — " +
                "бюджет 100 блоков / 4000 строк. Это храповик, а не идеал: если " +
                "уперлись, сокращайте историю, а не текущий факт.",
            blocks <= 100 && blockLines <= 4000,
        )
    }
}
