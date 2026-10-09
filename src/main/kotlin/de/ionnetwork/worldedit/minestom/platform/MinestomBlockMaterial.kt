package de.ionnetwork.worldedit.minestom.platform

import com.sk89q.worldedit.world.registry.BlockMaterial
import net.minestom.server.instance.block.Block

/**
 * WorldEdit's view of one block's physical behaviour, answered from Minestom's own block registry.
 *
 * WorldEdit ships a bundled `blocks.json` that could answer most of this, but it is frozen at
 * whatever Minecraft version that release was cut against; Minestom's registry is the version this
 * server actually speaks. Asking Minestom means a block added after WorldEdit's bundle still has
 * honest properties instead of silently reading as "not solid, no light, hardness 0" - which is
 * what masks like `#solid` and operations like `//smooth` and `//hollow` build on.
 *
 * Four answers have no Minestom equivalent and are stated as constants rather than guessed per
 * block, with the consequence of being wrong noted:
 * - [isFragileWhenPushed]/[isUnpushable] describe piston behaviour, which Minestom does not
 *   simulate. Reported as false/false; only WorldEdit's piston-aware helpers would care.
 * - [isTicksRandomly] drives nothing WorldEdit places, only informational output.
 * - [isBurnable] is fire-spread data Minestom does not carry.
 */
internal class MinestomBlockMaterial(private val block: Block) : BlockMaterial {

    override fun isAir(): Boolean = block.air()

    /** No cube-shape predicate in Minestom; full occlusion is the closest honest stand-in and is
     *  what the `#solid`-style masks actually want. */
    override fun isFullCube(): Boolean = block.occludes()

    override fun isOpaque(): Boolean = block.occludes()

    override fun isPowerSource(): Boolean = block.signalSource()

    override fun isLiquid(): Boolean = block.liquid()

    override fun isSolid(): Boolean = block.solid()

    override fun getHardness(): Float = block.hardness()

    override fun getResistance(): Float = block.explosionResistance()

    override fun getSlipperiness(): Float = block.friction()

    override fun getLightValue(): Int = block.lightEmission()

    override fun isFragileWhenPushed(): Boolean = false

    override fun isUnpushable(): Boolean = false

    override fun isTicksRandomly(): Boolean = false

    override fun isMovementBlocker(): Boolean = block.blocksMotion()

    override fun isBurnable(): Boolean = false

    override fun isToolRequired(): Boolean = block.requiresTool()

    override fun isReplacedDuringPlacement(): Boolean = block.replaceable()

    override fun isTranslucent(): Boolean = !block.occludes()

    /**
     * Whether breaking this block would spill an inventory. Minestom reports only that a block has
     * a block entity, not whether that entity holds items, so a sign reads as a container here.
     * The one consumer is WorldEdit's "clear container contents before replacing" step, which on
     * this platform is a no-op anyway (see `GuardedWorld.clearContainerBlockContents`), so erring
     * towards "yes" costs nothing.
     */
    override fun hasContainer(): Boolean = block.blockEntityType() != null
}
