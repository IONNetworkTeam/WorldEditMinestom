package de.ionnetwork.worldedit.minestom

import de.ionnetwork.worldedit.minestom.command.WorldEditCommandBridge
import net.minestom.server.instance.block.Block
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A builder on a 1.8 map should never be offered a block the map will refuse.
 *
 * Offering one is worse than offering nothing: they pick it from a list the server itself produced,
 * press enter, and get told no.
 */
class SuggestionFilterTest {

    /** Stands in for a LEGACY_1_8 map, which has no copper bulbs. */
    private val legacyMap = FakeEditTarget(rejectBlock = { it.name() == "minecraft:copper_bulb" })

    @Test
    fun `a block the map cannot place is not offered`() {
        assertFalse(WorldEditCommandBridge.placeableOn(legacyMap, "copper_bulb"))
        assertFalse(WorldEditCommandBridge.placeableOn(legacyMap, "minecraft:copper_bulb"))
    }

    @Test
    fun `a block the map can place is offered`() {
        assertTrue(WorldEditCommandBridge.placeableOn(legacyMap, "stone"))
        assertTrue(WorldEditCommandBridge.placeableOn(legacyMap, "minecraft:oak_stairs"))
    }

    /** A block state is judged by its block, not dropped for having properties. */
    @Test
    fun `a block with properties is judged by its block`() {
        assertTrue(WorldEditCommandBridge.placeableOn(legacyMap, "oak_stairs[facing=north]"))
        assertFalse(WorldEditCommandBridge.placeableOn(legacyMap, "copper_bulb[lit=true]"))
    }

    /**
     * A suggestion list is not only block names - it carries pattern and mask syntax, brush names and
     * directions. Anything that is not a block is left alone rather than guessed at, so the filter can
     * only ever remove a block the map would have refused.
     */
    @Test
    fun `non-block suggestions are left alone`() {
        for (entry in listOf("#clipboard", "^", "*", "##wool", "sphere", "north", "50%stone")) {
            assertTrue(
                WorldEditCommandBridge.placeableOn(legacyMap, entry),
                "'$entry' was filtered out, but it does not name a block"
            )
        }
    }

    @Test
    fun `a map with no profile restriction filters nothing`() {
        val modernMap = FakeEditTarget()
        assertTrue(WorldEditCommandBridge.placeableOn(modernMap, "copper_bulb"))
        assertTrue(WorldEditCommandBridge.placeableOn(modernMap, Block.STONE.name()))
    }
}
