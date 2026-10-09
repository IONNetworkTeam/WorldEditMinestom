package de.ionnetwork.worldedit.minestom.platform

import com.sk89q.worldedit.registry.state.BooleanProperty
import com.sk89q.worldedit.registry.state.DirectionalProperty
import com.sk89q.worldedit.registry.state.EnumProperty
import com.sk89q.worldedit.registry.state.IntegerProperty
import com.sk89q.worldedit.registry.state.Property
import com.sk89q.worldedit.util.Direction
import com.sk89q.worldedit.util.formatting.text.Component
import com.sk89q.worldedit.util.formatting.text.TranslatableComponent
import com.sk89q.worldedit.world.biome.BiomeData
import com.sk89q.worldedit.world.biome.BiomeType
import com.sk89q.worldedit.world.block.BlockState
import com.sk89q.worldedit.world.block.BlockType
import com.sk89q.worldedit.world.item.ItemType
import com.sk89q.worldedit.world.registry.BiomeRegistry
import com.sk89q.worldedit.world.registry.BlockCategoryRegistry
import com.sk89q.worldedit.world.registry.BlockMaterial
import com.sk89q.worldedit.world.registry.BlockRegistry
import com.sk89q.worldedit.world.registry.ItemCategoryRegistry
import com.sk89q.worldedit.world.registry.ItemMaterial
import com.sk89q.worldedit.world.registry.ItemRegistry
import com.sk89q.worldedit.world.registry.NullEntityRegistry
import com.sk89q.worldedit.world.registry.Registries
import net.minestom.server.instance.block.Block
import net.minestom.server.item.Material
import java.util.OptionalInt

/**
 * WorldEdit's registries, served from Minestom's.
 *
 * The bundled registries WorldEdit falls back to cannot do this job: `BundledBlockRegistry.getProperties`
 * returns an empty map outright (its own source comment is literally "Oof"), which would leave every
 * block on this platform with no block states at all - no `//set oak_stairs[facing=north]`, no
 * `//rotate` that knows which way a stair points, no property suggestions. Minestom, by contrast,
 * enumerates every state of every block ([Block.possibleStates]), so the properties can be recovered
 * exactly and this platform ends up with a *better* registry than the bundled fallback rather than a
 * degraded one.
 */
internal class MinestomRegistries : Registries {

    private val blockRegistry = MinestomBlockRegistry()
    private val itemRegistry = MinestomItemRegistry()
    private val biomeRegistry = MinestomBiomeRegistry()
    private val entityRegistry = NullEntityRegistry()

    /**
     * Block and item *tags* (`##wool`, `##logs`) are not wired. Minestom does expose registry tags,
     * but a missing category reads to WorldEdit as "no blocks match", which is a visible and
     * harmless failure, whereas a half-populated one would silently edit the wrong blocks.
     */
    private val blockCategoryRegistry = com.sk89q.worldedit.world.registry.NullBlockCategoryRegistry()
    private val itemCategoryRegistry = com.sk89q.worldedit.world.registry.NullItemCategoryRegistry()

    override fun getBlockRegistry(): BlockRegistry = blockRegistry
    override fun getItemRegistry(): ItemRegistry = itemRegistry
    override fun getEntityRegistry() = entityRegistry
    override fun getBiomeRegistry(): BiomeRegistry = biomeRegistry
    override fun getBlockCategoryRegistry(): BlockCategoryRegistry = blockCategoryRegistry
    override fun getItemCategoryRegistry(): ItemCategoryRegistry = itemCategoryRegistry
}

internal class MinestomBlockRegistry : BlockRegistry {

    override fun getRichName(blockType: BlockType): Component =
        TranslatableComponent.of(minestomBlock(blockType)?.translationKey() ?: blockType.id())

    override fun getMaterial(blockType: BlockType): BlockMaterial? =
        minestomBlock(blockType)?.let { MinestomBlockMaterial(it) }

    /**
     * Recovers a block's properties by diffing its states.
     *
     * Minestom exposes a state's properties as plain `name -> value` strings and never the property
     * definition itself, so the set of legal values per property is collected by walking every state
     * the block has. That walk is the only way to get from Minestom's representation to WorldEdit's
     * typed one, and it is why this is computed once per block type and cached rather than per call.
     */
    override fun getProperties(blockType: BlockType): Map<String, Property<*>> =
        propertyCache.getOrPut(blockType.id()) { computeProperties(blockType) }

    /**
     * Minestom's state id is exactly what the client is sent, so WorldEdit's fast per-state integer
     * path works here rather than falling back to per-property comparisons.
     */
    override fun getInternalBlockStateId(state: BlockState): OptionalInt {
        val block = BlockAdapter.toMinestom(state) ?: return OptionalInt.empty()
        return OptionalInt.of(block.stateId())
    }

    private fun computeProperties(blockType: BlockType): Map<String, Property<*>> {
        val block = minestomBlock(blockType) ?: return emptyMap()
        val values = LinkedHashMap<String, LinkedHashSet<String>>()
        for (state in block.possibleStates()) {
            for ((name, value) in state.properties()) {
                values.getOrPut(name) { LinkedHashSet() }.add(value)
            }
        }
        return values.mapValues { (name, options) -> typedProperty(name, options.toList()) }
    }

    /**
     * Picks the WorldEdit property type from the shape of the values, because Minestom only hands
     * over strings.
     *
     * The direction case is the one that earns its keep: WorldEdit's `BlockTransformExtent` rotates
     * and flips a block by looking for a [DirectionalProperty] specifically, so a `facing` property
     * left as a plain [EnumProperty] would make `//rotate` leave every stair, torch and chest
     * pointing the way it already did. `axis` (x/y/z) deliberately does *not* match, because
     * WorldEdit handles that one by name as an enum.
     */
    private fun typedProperty(name: String, options: List<String>): Property<*> = when {
        options.size == 2 && options.containsAll(BOOLEAN_VALUES) -> BooleanProperty(name, options.map { it.toBoolean() })
        options.all { it.toIntOrNull() != null } -> IntegerProperty(name, options.map { it.toInt() })
        options.all { it in DIRECTION_NAMES } -> DirectionalProperty(name, options.map { DIRECTION_NAMES.getValue(it) })
        else -> EnumProperty(name, options)
    }

    private companion object {
        val BOOLEAN_VALUES = listOf("true", "false")

        /** Only the six plain directions; a compound value like a jigsaw's `down_east` falls through
         *  to [EnumProperty], which is what WorldEdit's own vanilla registries do with it too. */
        val DIRECTION_NAMES: Map<String, Direction> = mapOf(
            "north" to Direction.NORTH,
            "south" to Direction.SOUTH,
            "east" to Direction.EAST,
            "west" to Direction.WEST,
            "up" to Direction.UP,
            "down" to Direction.DOWN
        )

        val propertyCache = HashMap<String, Map<String, Property<*>>>()

        fun minestomBlock(blockType: BlockType): Block? = Block.fromKey(blockType.id())
    }
}

@Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
internal class MinestomItemRegistry : ItemRegistry {

    override fun getRichName(itemType: ItemType): Component =
        TranslatableComponent.of(Material.fromKey(itemType.id())?.translationKey() ?: itemType.id())

    override fun getMaterial(itemType: ItemType): ItemMaterial? {
        val material = Material.fromKey(itemType.id()) ?: return null
        return object : ItemMaterial {
            override fun getMaxStackSize(): Int = material.maxStackSize()
            override fun getMaxDamage(): Int = 0
        }
    }
}

/**
 * Biomes exist so `//setbiome` and biome masks resolve a name, but carry no climate data: WorldEdit's
 * [BiomeData] is only ever used for its display name, and Minestom's biome registry is per-server
 * rather than a fixed vanilla list.
 */
@Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
internal class MinestomBiomeRegistry : BiomeRegistry {

    override fun getRichName(biomeType: BiomeType): Component =
        TranslatableComponent.of("biome.${biomeType.id().replace(':', '.')}")

    @Suppress("DEPRECATION", "removal")
    override fun getData(biomeType: BiomeType): BiomeData? = null
}
