package de.ionnetwork.worldedit.minestom.command

import com.sk89q.worldedit.LocalSession
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.session.ClipboardHolder
import de.ionnetwork.minestomconversion.extension.edit.EditClipboard
import de.ionnetwork.minestomconversion.extension.edit.EditPosition
import de.ionnetwork.minestomconversion.extension.edit.EditSelection
import de.ionnetwork.minestomconversion.extension.edit.EditTarget
import de.ionnetwork.worldedit.minestom.platform.BlockAdapter
import de.ionnetwork.worldedit.minestom.world.GuardedWorld
import net.minestom.server.instance.block.Block
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps WorldEdit's per-player session in step with the build server's.
 *
 * Both systems have a selection and a clipboard, and a builder has exactly one of each. The build
 * server's is authoritative: its wand sets the selection and its GUIs and `/schem save` read the
 * clipboard, so WorldEdit's copies are seeded from it before a command and written back after.
 *
 * Without the write-back half, `//expand`, `//shift` and `//copy` would appear to work and then be
 * silently discarded the next time the wand was clicked. Without the seed half, a builder who
 * selected a region with the wand would be told they have no selection.
 */
internal object SessionBridge {

    /**
     * What was last handed across, per player, so an unchanged clipboard is not converted twice per
     * command. A clipboard is up to half a million blocks; converting one on every `//set` would be
     * the most expensive thing a small command does.
     */
    private data class Synced(val ion: EditClipboard?, val worldEdit: Clipboard?)

    private val lastSynced = ConcurrentHashMap<UUID, Synced>()

    fun forget(player: UUID) {
        lastSynced.remove(player)
    }

    fun seed(player: UUID, session: LocalSession, world: GuardedWorld, target: EditTarget) {
        seedSelection(session, world, target)
        seedClipboard(player, session, target.clipboard())
    }

    fun writeBack(player: UUID, session: LocalSession, world: GuardedWorld, target: EditTarget) {
        writeBackSelection(session, world, target)
        writeBackClipboard(player, session, target)
    }

    /**
     * Seeds corner by corner rather than from the finished box, so a selection the builder has only
     * half made - one wand click, one `//pos1` - is half made in WorldEdit too, and the next corner
     * completes it instead of starting over.
     */
    private fun seedSelection(session: LocalSession, world: GuardedWorld, target: EditTarget) {
        session.setRegionSelector(
            world,
            CornerSelector(world, target.pos1()?.toWorldEdit(), target.pos2()?.toWorldEdit())
        )
    }

    /**
     * Only cuboid selections travel back. WorldEdit can select polygons, cylinders and convex hulls,
     * and the build server's selection is a box - so a builder who switched to a polygonal selector
     * keeps it inside WorldEdit for the rest of their session, and the box simply stops tracking it
     * rather than being replaced by a wrong bounding box that the wand would then appear to own.
     */
    private fun writeBackSelection(session: LocalSession, world: GuardedWorld, target: EditTarget) {
        // Still the selector this bridge seeded: its corners travel back as they are, set or not,
        // which is what carries a lone `//pos1` across. Commands that build a fresh selector of their
        // own (`//chunk`) only ever leave a complete box, which the path below handles.
        val selector = runCatching { session.getRegionSelector(world) }.getOrNull()
        if (selector is CornerSelector) {
            target.setPositions(selector.first?.toEdit(), selector.second?.toEdit())
            return
        }
        val region = runCatching { session.getSelection(world) }.getOrNull()
        if (region !is CuboidRegion) return
        val min = region.minimumPoint
        val max = region.maximumPoint
        target.setSelection(EditSelection(min.x(), min.y(), min.z(), max.x(), max.y(), max.z()))
    }

    private fun EditPosition.toWorldEdit(): BlockVector3 = BlockVector3.at(x(), y(), z())

    private fun BlockVector3.toEdit(): EditPosition = EditPosition(x(), y(), z())

    private fun seedClipboard(player: UUID, session: LocalSession, ion: EditClipboard?) {
        val synced = lastSynced[player]
        if (synced != null && synced.ion === ion) return
        if (ion == null) {
            lastSynced[player] = Synced(null, null)
            return
        }
        val clipboard = toWorldEdit(ion)
        session.clipboard = ClipboardHolder(clipboard)
        lastSynced[player] = Synced(ion, clipboard)
    }

    private fun writeBackClipboard(player: UUID, session: LocalSession, target: EditTarget) {
        val clipboard = runCatching { session.clipboard.clipboard }.getOrNull() ?: return
        if (lastSynced[player]?.worldEdit === clipboard) return
        val ion = toIon(clipboard)
        target.setClipboard(ion)
        lastSynced[player] = Synced(ion, clipboard)
    }

    private fun toWorldEdit(ion: EditClipboard): Clipboard {
        val max = BlockVector3.at(ion.width() - 1, ion.height() - 1, ion.length() - 1)
        val clipboard = BlockArrayClipboard(CuboidRegion(BlockVector3.ZERO, max))
        clipboard.origin = BlockVector3.at(ion.originX(), ion.originY(), ion.originZ())
        for (y in 0 until ion.height()) {
            for (z in 0 until ion.length()) {
                for (x in 0 until ion.width()) {
                    clipboard.setBlock(
                        BlockVector3.at(x, y, z),
                        BlockAdapter.toWorldEditFull(ion.blockAt(x, y, z))
                    )
                }
            }
        }
        return clipboard
    }

    private fun toIon(clipboard: Clipboard): EditClipboard {
        val min = clipboard.minimumPoint
        val dimensions = clipboard.dimensions
        val width = dimensions.x()
        val height = dimensions.y()
        val length = dimensions.z()
        val blocks = Array(width * height * length) { Block.AIR }
        for (y in 0 until height) {
            for (z in 0 until length) {
                for (x in 0 until width) {
                    val block = clipboard.getFullBlock(min.add(x, y, z))
                    blocks[((y * length) + z) * width + x] = BlockAdapter.toMinestom(block) ?: Block.AIR
                }
            }
        }
        val origin = clipboard.origin.subtract(min)
        return EditClipboard(width, height, length, origin.x(), origin.y(), origin.z(), blocks)
    }
}
