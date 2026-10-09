package de.ionnetwork.worldedit.minestom

import de.ionnetwork.minestomconversion.extension.edit.EditTarget
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Keeps the runtime compatibility check honest.
 *
 * The extension refuses to load when the server's editing SPI is missing something it calls, because
 * the two jars ship separately and a mismatch otherwise surfaces as `NoSuchMethodError` from deep
 * inside WorldEdit, once per command. That check reads from a hand-written list of method names,
 * which can go stale in both directions - and the harmful direction is naming a method that no longer
 * exists, because then a perfectly matched pair refuses to load and says the server jar is old when
 * it is not. That has already happened once.
 */
class SpiCompatibilityTest {

    @Test
    fun `every method the compatibility check requires actually exists`() {
        val available = EditTarget::class.java.methods.mapTo(HashSet()) { it.name }
        val phantom = WorldEditMinestomExtension.REQUIRED_TARGET_METHODS.filterNot { it in available }
        assertEquals(
            emptyList(), phantom,
            "the load-time check requires methods EditTarget does not have, so it would refuse to " +
                "load against a correct server jar"
        )
    }
}
