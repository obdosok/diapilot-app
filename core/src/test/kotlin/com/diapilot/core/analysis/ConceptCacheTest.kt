package com.diapilot.core.analysis

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `conceptFor` and `effectiveConcepts` are MEMOISED (a lookup cost 896 KB and
 * 0.68 ms, and the deconvolution asks the same names ~16 times per twin build).
 * Memoisation is only correct while every writer of the two globals clears the
 * cache, so this pins that — a cached answer surviving an edit would silently
 * pool a dish under the concept the user just corrected away from.
 *
 * The order inside each test is the point: ASK FIRST (populating the cache),
 * THEN edit, then ask again. Asking only after the edit passes even with no
 * invalidation at all, which is the shape of test this file exists to avoid.
 */
class ConceptCacheTest {

    @After
    fun clean() {
        setUserConceptOverrides(emptyList())
        setUserConceptAliases(emptyMap())
    }

    @Test
    fun `an override AFTER a cached lookup is seen`() {
        assertEquals(CarbSpeed.FAST, conceptFor("хлеб")?.carbSpeed)     // caches "bread"
        setUserConceptOverrides(listOf(ConceptOverride(id = "bread", carbSpeed = CarbSpeed.SLOW)))
        assertEquals(CarbSpeed.SLOW, conceptFor("хлеб")?.carbSpeed)
    }

    @Test
    fun `a name that mapped to NOTHING is re-asked after an alias is added`() {
        // The null branch has its own cache entry (ConcurrentHashMap forbids
        // nulls, so it stores a sentinel) — the easiest one to get wrong.
        assertNull(conceptFor("абракадабра"))
        setUserConceptAliases(mapOf("абракадабра" to "pizza"))
        assertEquals("pizza", conceptFor("абракадабра")?.id)
    }

    @Test
    fun `an alias edit AFTER a cached lookup is seen`() {
        assertEquals("pizza", conceptFor("пицца")?.id)                  // caches "pizza"
        setUserConceptAliases(mapOf("пицца" to "bread"))
        assertEquals("bread", conceptFor("пицца")?.id)
    }

    @Test
    fun `effectiveConcepts is rebuilt after an edit, not served from the cache`() {
        val before = effectiveConcepts()
        assertEquals(before, effectiveConcepts())                       // stable while nothing changes
        setUserConceptOverrides(listOf(ConceptOverride(id = "pizza", carbSpeed = CarbSpeed.SLOW)))
        val after = effectiveConcepts()
        assertNotSame(before, after)
        assertEquals(CarbSpeed.SLOW, after.first { it.id == "pizza" }.carbSpeed)
    }

    @Test
    fun `normalisation still applies — the cache key is the normalised name`() {
        assertEquals(conceptFor("пицца")?.id, conceptFor("  ПИЦЦА ")?.id)
    }
}
