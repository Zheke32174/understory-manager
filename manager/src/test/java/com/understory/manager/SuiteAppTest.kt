package com.understory.manager

import com.understory.security.SuiteCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM checks on the Manager's static suite registry. No Android framework
 * needed — these guard the honesty invariants of the [SuiteApp] table itself.
 */
class SuiteAppTest {

    @Test
    fun all_covers_every_entry_exactly_once() {
        val all = SuiteApp.all()
        assertEquals(SuiteApp.entries.size, all.size)
        assertEquals(SuiteApp.entries.toSet(), all.toSet())
    }

    @Test
    fun every_package_is_a_distinct_understory_id() {
        val ids = SuiteApp.entries.map { it.packageId }
        // No duplicates.
        assertEquals(ids.size, ids.toSet().size)
        // All under the suite namespace, none suffixed.
        ids.forEach { id ->
            assertTrue("$id must be an understory package", id.startsWith("com.understory."))
            assertFalse("$id must not carry a build suffix", id.endsWith(".eng"))
        }
    }

    @Test
    fun byPackage_round_trips_and_rejects_unknown() {
        SuiteApp.entries.forEach { app ->
            assertEquals(app, SuiteApp.byPackage(app.packageId))
        }
        assertNull(SuiteApp.byPackage("com.example.notasuiteapp"))
        // The Manager's own package is deliberately NOT a member.
        assertNull(SuiteApp.byPackage("com.understory.manager"))
    }

    @Test
    fun browser_advertises_no_capability_but_stays_a_member() {
        // BEACON honesty: browser v1 has no peer-invocable surface, so its
        // expected capability must be null — the dashboard shows no chip for it.
        assertNull(SuiteApp.BROWSER.expectedCapability)
        // But it is still a real, categorized suite member.
        assertNotNull(SuiteApp.byPackage(SuiteApp.BROWSER.packageId))
        assertEquals(SuiteCategory.SECURITY, SuiteApp.BROWSER.category)
    }

    @Test
    fun vault_apps_are_exactly_the_vault_category() {
        val vault = SuiteApp.vaultApps().toSet()
        assertEquals(
            SuiteApp.entries.filter { it.category == SuiteCategory.VAULT }.toSet(),
            vault,
        )
        // The four vault apps the recovery center reminds about.
        assertTrue(SuiteApp.PASSGEN in vault)
        assertTrue(SuiteApp.AEGIS in vault)
        assertTrue(SuiteApp.VAULTFOLDER in vault)
        assertTrue(SuiteApp.BACKUPS in vault)
    }

    @Test
    fun capabilityLabel_is_defined_for_every_capability() {
        // Exhaustive `when` in capabilityLabel means a new enum value forces an
        // update; this asserts each yields a non-blank human label.
        SuiteCapability.entries.forEach { cap ->
            assertTrue("label for $cap must be non-blank", capabilityLabel(cap).isNotBlank())
        }
    }
}
