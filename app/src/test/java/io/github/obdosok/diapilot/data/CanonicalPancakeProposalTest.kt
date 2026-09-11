package io.github.obdosok.diapilot.data

import com.diapilot.core.analysis.FoodPhysicalFormV2
import com.diapilot.core.analysis.FoodStructureAcceptanceV1
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The canonical pancake rows, and the two things about them that can silently
 * rot: an alias that no longer matches a real note, and a form/speed that
 * drifts back toward the pre-v3 parse.
 *
 * Why canonical rows at all: measured, the identity key was unstable
 * on most pancake notes across their OWN draws, so many notes of the same
 * few meals could never be recognised as repeats and the dish never earned a
 * timing pool. One consensus analysis per dish text is the fix — three draws of
 * the v3 prompt, mode of the categorical fields, median of the numeric ones.
 * Form came back SOFT_SOLID in every draw, the fast fraction 0.96-1.00.
 *
 * Nothing here applies itself: the rows are inert asset text until the user
 * taps accept, and acceptance stamps `analysis_known_at_ms` so a causal replay
 * as-of an earlier instant still cannot see them.
 */
class CanonicalPancakeProposalTest {
    private val asset: JSONObject = JSONObject(
        File("src/main/assets/models/food_structure_proposal_v1.json")
            .let { if (it.exists()) it else File("app/src/main/assets/models/food_structure_proposal_v1.json") }
            .readText(),
    )

    private fun dish(id: String): JSONObject {
        val arr = asset.getJSONArray("dishes")
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            if (d.getString("id") == id) return d
        }
        throw AssertionError("no dish $id in the proposal")
    }

    private fun proposedOf(id: String): FoodStructureAcceptanceV1.Proposed {
        val d = dish(id)
        val aliases = d.getJSONArray("aliases")
        return FoodStructureAcceptanceV1.Proposed(
            id = d.getString("id"), title = d.getString("title"),
            aliases = (0 until aliases.length()).map(aliases::getString),
            form = FoodPhysicalFormV2.valueOf(d.getString("form")),
            fast = d.getDouble("fast"), medium = d.getDouble("medium"), slow = d.getDouble("slow"),
            fiberG = d.optDouble("fiber_g").takeIf { !it.isNaN() },
            proteinG = d.optDouble("protein_g").takeIf { !it.isNaN() },
            fatG = d.optDouble("fat_g").takeIf { !it.isNaN() },
            confidence = d.getDouble("confidence"),
        )
    }

    /** Representative note texts, in the same shapes the app's notes take. */
    private val realNotes = listOf(
        "2 блина по 80г",
        "2 блина",
        "2 блина с сыром, ветчиной, горчицей и кетчупом",
        "2 блина с сыром, ветчиной, горчицей, кетчупом",
        "блин с фисташковой пастой",
        "блин 70г с фисташковой пастой",
        "блин 70г с клубничным вареньем",
        "блин с клубничным вареньем",
    )

    @Test fun `every pancake alias matches a note text`() {
        listOf("pancakes_plain", "pancakes_ham_cheese", "pancake_pistachio", "pancake_jam").forEach { id ->
            val p = proposedOf(id)
            p.aliases.forEach { alias ->
                assertTrue(
                    "alias \"$alias\" of dish $id does not match any note text",
                    realNotes.any { FoodStructureAcceptanceV1.matches(p, it) && it.equals(alias, true) },
                )
            }
        }
    }

    /** Both spellings of the savoury pancake note — with "and" and with a comma —
     * must land on the SAME dish, or the meals split 1+2 and the pool misses
     * the n>=3 bar by one. */
    @Test fun `both spellings of the savoury pancake reach one dish`() {
        val p = proposedOf("pancakes_ham_cheese")
        val matched = realNotes.count { FoodStructureAcceptanceV1.matches(p, it) }
        assertEquals("both wordings of the savoury pancake must land on one dish", 2, matched)
    }

    @Test fun `the consensus values are the v3 ones, not the pre-fix parse`() {
        listOf("pancakes_plain", "pancakes_ham_cheese", "pancake_pistachio", "pancake_jam").forEach { id ->
            val d = dish(id)
            assertEquals("a pancake is soft-solid, not SOLID/MIXED", "SOFT_SOLID", d.getString("form"))
            assertTrue(
                "white-flour batter and sugary filling are fast; fat slows it, and it is recorded as a number",
                d.getDouble("fast") >= 0.9,
            )
            assertTrue("fat must be recorded as a number", d.getDouble("fat_g") > 0.0)
            assertTrue(
                "the row must honestly say that dish selection was not blind",
                !d.getBoolean("blind"),
            )
        }
    }
}
