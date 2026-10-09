package de.ionnetwork.worldedit.minestom

import de.ionnetwork.worldedit.minestom.platform.BlockAdapter
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.kyori.adventure.nbt.StringBinaryTag
import net.minestom.server.instance.block.Block
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlockAdapterTest {

    @BeforeTest
    fun boot() {
        WorldEditTestPlatform.ensureStarted()
    }

    @Test
    fun `a plain block round trips`() {
        val stone = Block.STONE
        assertEquals(stone, BlockAdapter.toMinestom(BlockAdapter.toWorldEdit(stone)))
    }

    /**
     * The property case is the whole reason this platform builds its own block registry: WorldEdit's
     * bundled fallback reports every block as having no properties at all, which would quietly turn
     * `//set oak_stairs[facing=north,half=top]` into a default-facing stair.
     */
    @Test
    fun `block properties survive the round trip`() {
        val stairs = Block.OAK_STAIRS.withProperties(mapOf("facing" to "north", "half" to "top"))
        val roundTripped = BlockAdapter.toMinestom(BlockAdapter.toWorldEdit(stairs))
        assertEquals(stairs, roundTripped)
        assertEquals("north", roundTripped?.getProperty("facing"))
        assertEquals("top", roundTripped?.getProperty("half"))
    }

    /**
     * `facing` has to reach WorldEdit as a DirectionalProperty specifically, because that is the only
     * type its rotate/flip transform recognises - as an enum of strings it would compare equal and
     * leave every stair pointing the way it already did.
     */
    @Test
    fun `facing is directional so rotation can act on it`() {
        val type = BlockAdapter.toWorldEdit(Block.OAK_STAIRS).blockType
        val facing = type.getProperty<Any>("facing")
        assertNotNull(facing)
        assertTrue(
            facing is com.sk89q.worldedit.registry.state.DirectionalProperty,
            "facing came back as ${facing::class.simpleName}, which //rotate ignores"
        )
    }

    @Test
    fun `an integer property stays integral`() {
        val type = BlockAdapter.toWorldEdit(Block.OAK_SIGN).blockType
        val rotation = type.getProperty<Any>("rotation")
        assertNotNull(rotation)
        assertTrue(
            rotation is com.sk89q.worldedit.registry.state.IntegerProperty,
            "rotation came back as ${rotation::class.simpleName}"
        )
    }

    @Test
    fun `a boolean property stays boolean`() {
        val type = BlockAdapter.toWorldEdit(Block.OAK_FENCE).blockType
        val north = type.getProperty<Any>("north")
        assertNotNull(north)
        assertTrue(
            north is com.sk89q.worldedit.registry.state.BooleanProperty,
            "north came back as ${north::class.simpleName}"
        )
    }

    /** Without this, `//copy` then `//paste` would move a sign's post and lose its text. */
    @Test
    fun `block entity NBT survives the round trip`() {
        val text = CompoundBinaryTag.builder()
            .put(
                "messages",
                ListBinaryTag.listBinaryTag(
                    net.kyori.adventure.nbt.BinaryTagTypes.STRING,
                    listOf(StringBinaryTag.stringBinaryTag("\"hello\""))
                )
            )
            .build()
        val nbt = CompoundBinaryTag.builder()
            .put("front_text", text)
            .putInt("some_number", 7)
            .putByte("some_flag", 1)
            .build()
        val sign = Block.OAK_SIGN.withNbt(nbt)

        val roundTripped = BlockAdapter.toMinestom(BlockAdapter.toWorldEditFull(sign))

        assertNotNull(roundTripped)
        assertEquals(nbt, roundTripped.nbt())
    }
}
