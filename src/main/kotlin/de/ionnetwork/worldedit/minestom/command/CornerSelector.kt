package de.ionnetwork.worldedit.minestom.command

import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector
import com.sk89q.worldedit.world.World

/**
 * A cuboid selector whose two corners can be read and seeded one at a time.
 *
 * WorldEdit's own selector keeps each corner in a protected field and only lets a caller see a
 * complete box - asking a half-made selection for its region throws. That is fine inside WorldEdit
 * and fatal across the bridge: `//pos1` on its own produces exactly such a half selection, so the
 * corner it set could never reach the build server, and the next command's seed - which starts from
 * the build server's selection - threw it away. `//pos1` then `//pos2` selected nothing.
 */
internal class CornerSelector(
    world: World,
    first: BlockVector3?,
    second: BlockVector3?
) : CuboidRegionSelector(world) {

    init {
        position1 = first
        position2 = second
        first?.let { region.pos1 = it }
        second?.let { region.pos2 = it }
    }

    val first: BlockVector3? get() = position1

    val second: BlockVector3? get() = position2
}
