package de.ionnetwork.worldedit.minestom

import de.ionnetwork.worldedit.minestom.world.EditBuffer
import net.minestom.server.instance.block.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dense edit buffer, which is what made a multi-million-block edit affordable.
 *
 * Its packing is the risky part: a build map's coordinates are routinely negative, and a
 * sign-extension mistake does not throw - it silently writes the block somewhere else, in a part of
 * the map the builder was not looking at.
 */
class EditBufferTest {

    @Test
    fun `positions round trip, including negative ones`() {
        val positions = listOf(
            Triple(0, 0, 0),
            Triple(1, 2, 3),
            Triple(-1, -1, -1),
            Triple(-3000, -64, 2999),
            Triple(2_000_000, 319, -2_000_000),
            Triple(-30_000_000, -2048, 30_000_000)
        )
        for ((x, y, z) in positions) {
            val packed = EditBuffer.pack(x, y, z)
            assertEquals(x, EditBuffer.unpackX(packed), "x for ($x, $y, $z)")
            assertEquals(y, EditBuffer.unpackY(packed), "y for ($x, $y, $z)")
            assertEquals(z, EditBuffer.unpackZ(packed), "z for ($x, $y, $z)")
        }
    }

    @Test
    fun `distinct positions never collide`() {
        val seen = HashSet<Long>()
        for (x in -2..2) {
            for (y in -2..2) {
                for (z in -2..2) {
                    assertTrue(seen.add(EditBuffer.pack(x, y, z)), "($x, $y, $z) collided")
                }
            }
        }
    }

    @Test
    fun `a write reads back and a later write replaces it`() {
        val buffer = EditBuffer()
        buffer.put(-5, -64, 12, Block.STONE)
        assertEquals(Block.STONE, buffer.get(-5, -64, 12))

        buffer.put(-5, -64, 12, Block.DIRT)
        assertEquals(Block.DIRT, buffer.get(-5, -64, 12))
        assertEquals(1, buffer.size(), "replacing a position should not add a second write")
    }

    @Test
    fun `an untouched position reads as nothing`() {
        val buffer = EditBuffer()
        buffer.put(0, 0, 0, Block.STONE)
        assertNull(buffer.get(1, 0, 0))
    }

    /** The bounding box is what the tile-box rule is answered from, so it has to cover every write. */
    @Test
    fun `the bounding box covers every write`() {
        val buffer = EditBuffer()
        buffer.put(-10, -64, 5, Block.STONE)
        buffer.put(30, 100, -20, Block.DIRT)
        buffer.put(0, 0, 0, Block.STONE)

        assertEquals(-10, buffer.minX())
        assertEquals(-64, buffer.minY())
        assertEquals(-20, buffer.minZ())
        assertEquals(30, buffer.maxX())
        assertEquals(100, buffer.maxY())
        assertEquals(5, buffer.maxZ())
    }

    /** The per-block rules are asked about states, not positions - one question for a million blocks
     *  of the same block is the difference between usable and not. */
    @Test
    fun `distinct blocks collapse however many positions use them`() {
        val buffer = EditBuffer()
        for (x in 0 until 1000) buffer.put(x, 0, 0, Block.STONE)
        buffer.put(0, 1, 0, Block.DIRT)

        assertEquals(1001, buffer.size())
        assertEquals(setOf(Block.STONE, Block.DIRT), buffer.distinctBlocks())
    }

    @Test
    fun `forEach visits every write exactly once`() {
        val buffer = EditBuffer()
        buffer.put(-1, -1, -1, Block.STONE)
        buffer.put(2, 3, 4, Block.DIRT)

        val seen = mutableListOf<Triple<Int, Int, Int>>()
        buffer.forEach { x, y, z, _ -> seen += Triple(x, y, z) }

        assertEquals(setOf(Triple(-1, -1, -1), Triple(2, 3, 4)), seen.toSet())
        assertEquals(2, seen.size)
    }
}
