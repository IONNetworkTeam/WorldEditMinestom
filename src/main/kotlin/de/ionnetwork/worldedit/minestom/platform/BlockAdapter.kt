package de.ionnetwork.worldedit.minestom.platform

import com.sk89q.worldedit.registry.state.Property
import com.sk89q.worldedit.util.Direction
import com.sk89q.worldedit.world.block.BaseBlock
import com.sk89q.worldedit.world.block.BlockState
import com.sk89q.worldedit.world.block.BlockStateHolder
import com.sk89q.worldedit.world.block.BlockType
import com.sk89q.worldedit.world.block.BlockTypes
import net.minestom.server.instance.block.Block
import java.util.concurrent.ConcurrentHashMap

/**
 * Translates between Minestom's [Block] and WorldEdit's [BlockState]/[BaseBlock].
 *
 * Both directions are cached, because this sits on the hot path of every edit: a 500k-block `//set`
 * converts once, but a `//replace` or a `//copy` converts once *per position*. The caches are keyed
 * on each side's own canonical state identity - Minestom's dense integer state id, and WorldEdit's
 * interned [BlockState] instance - so a hit costs a map lookup and nothing else.
 *
 * Conversion is by property name and value string in both directions rather than by state id: the
 * two registries agree on Minecraft's names, but nothing guarantees they agree on state *numbering*
 * if WorldEdit's bundled data and Minestom's registry were cut against different Minecraft versions.
 * Names are the contract; ids are an implementation detail on each side.
 */
internal object BlockAdapter {

    private val toWorldEditCache = ConcurrentHashMap<Int, BlockState>()
    private val toMinestomCache = ConcurrentHashMap<BlockState, Block>()

    /** WorldEdit's air, used whenever a block has no counterpart. Resolved lazily: [BlockTypes]'
     *  fields are populated by registry lookup, so touching it before registration yields null. */
    private val air: BlockState
        get() = BlockTypes.AIR?.defaultState ?: error("worldedit block registry not initialized")

    fun toWorldEdit(block: Block): BlockState =
        toWorldEditCache.getOrPut(block.stateId()) { computeWorldEdit(block) }

    /**
     * The full block, i.e. with block-entity NBT when Minestom carries any. Minestom keeps a sign's
     * text and a chest's items on the block instance itself rather than in a separate tile-entity
     * object, so there is no second lookup to do here.
     */
    fun toWorldEditFull(block: Block): BaseBlock {
        val state = toWorldEdit(block)
        val nbt = block.nbt() ?: return state.toBaseBlock()
        return state.toBaseBlock(NbtAdapter.toLin(nbt))
    }

    /** `null` when WorldEdit names a block this server's registry does not have. */
    fun toMinestom(state: BlockStateHolder<*>): Block? {
        val immutable = state.toImmutableState()
        val base = toMinestomCache.getOrPut(immutable) { computeMinestom(immutable) ?: return null }
        if (state !is BaseBlock) return base
        val nbt = state.nbtReference?.value ?: return base
        return base.withNbt(NbtAdapter.toAdventure(nbt))
    }

    private fun computeWorldEdit(block: Block): BlockState {
        val type: BlockType = BlockType.REGISTRY.get(block.key().asString()) ?: return air
        var state = type.defaultState
        for ((name, value) in block.properties()) {
            val property = type.getProperty<Any>(name) ?: continue
            val typed = property.getValueFor(value) ?: continue
            state = state.with(property, typed)
        }
        return state
    }

    private fun computeMinestom(state: BlockState): Block? {
        val base = Block.fromKey(state.blockType.id()) ?: return null
        val properties = state.states
        if (properties.isEmpty()) return base
        val asStrings = HashMap<String, String>(properties.size)
        for ((property, value) in properties) {
            asStrings[property.name()] = stringify(property, value)
        }
        return base.withProperties(asStrings)
    }

    /**
     * Minestom names property values the way the block-state format does - lowercase, no namespace -
     * which is what every type here already produces except [Direction], whose enum constants are
     * uppercase.
     */
    private fun stringify(property: Property<*>, value: Any?): String = when (value) {
        is Direction -> value.name.lowercase()
        is Boolean -> if (value) "true" else "false"
        null -> error("null value for property ${property.name()}")
        else -> value.toString()
    }
}
