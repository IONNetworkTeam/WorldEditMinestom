package de.ionnetwork.worldedit.minestom

import de.ionnetwork.minestomconversion.extension.edit.BlockSection
import de.ionnetwork.minestomconversion.extension.edit.EditChange
import de.ionnetwork.minestomconversion.extension.edit.EditChanges
import de.ionnetwork.minestomconversion.extension.edit.EditClipboard
import de.ionnetwork.minestomconversion.extension.edit.EditProgress
import de.ionnetwork.minestomconversion.extension.edit.EditRejection
import de.ionnetwork.minestomconversion.extension.edit.EditSelection
import de.ionnetwork.minestomconversion.extension.edit.EditTarget
import net.minestom.server.instance.block.Block
import java.util.function.IntConsumer

/**
 * A build map with no server behind it.
 *
 * This is the payoff of keeping the Minestom instance on the build server's side of the SPI: the
 * whole editing path - WorldEdit's operations, the extent, the gate, the batch - runs against a
 * HashMap, so what a `//set` or a refused block actually does can be asserted rather than described.
 *
 * [rejectBlock] is the one knob: it stands in for whatever rule refused a block (a map's LEGACY_1_8
 * profile, a jump tile's palette), because [GuardedWorld] cannot tell those apart and should not.
 */
internal class FakeEditTarget(
    private val rejectBlock: (Block) -> Boolean = { false },
    private val bounds: IntRange? = null,
    private val maxBlocks: Int = 500_000
) : EditTarget {

    val world = HashMap<Triple<Int, Int, Int>, Block>()
    val applied = mutableListOf<EditChange>()
    private val staged = mutableListOf<EditChange>()
    val messages = mutableListOf<String>()
    val prefixed = mutableListOf<String>()
    val progressReports = mutableListOf<EditProgress>()
    var progressCleared = false
        private set
    // Backing fields, not `selection`/`clipboard`: a Kotlin `var` of that name would generate the
    // same setSelection/setClipboard JVM signatures the interface already declares.
    var currentSelection: EditSelection? = null
    var currentClipboard: EditClipboard? = null
    var loadedChunks = mutableSetOf<Pair<Int, Int>>()

    /** How many times the target was asked to load chunks - once per edit is the point. */
    var ensureLoadedCalls = 0
        private set

    /** Every block written, in order - what the player would see. */
    val appliedChanges: List<EditChange> get() = applied

    override fun mapId(): String = "fake-map"

    override fun runOnMapThread(work: Runnable) = work.run()

    override fun ensureLoaded(chunks: LongArray) {
        ensureLoadedCalls++
        for (packed in chunks) loadedChunks += (packed shr 32).toInt() to packed.toInt()
    }

    override fun minY(): Int = -64

    override fun maxY(): Int = 320

    override fun maxBlocks(): Int = maxBlocks

    override fun blockAt(x: Int, y: Int, z: Int): Block = world[Triple(x, y, z)] ?: Block.AIR

    override fun check(changes: EditChanges): EditRejection? {
        if (changes.size() == 0) return null
        if (changes.size() > maxBlocks) {
            return EditRejection.of("buildserver.edit.reject.too_large", "count", changes.size().toString())
        }
        changes.distinctBlocks().firstOrNull { rejectBlock(it) }?.let {
            return EditRejection.of("buildserver.edit.reject.profile", "state", it.state())
        }
        if (bounds != null &&
            (changes.minX() !in bounds || changes.maxX() !in bounds ||
                changes.minZ() !in bounds || changes.maxZ() !in bounds)
        ) {
            return EditRejection.of("buildserver.error.tile_out_of_bounds")
        }
        return null
    }

    override fun canEverPlace(block: Block): Boolean = !rejectBlock(block)

    override fun beginEdit() {
        staged.clear()
    }

    override fun stage(changes: EditChanges) {
        changes.forEach { x, y, z, block -> staged += EditChange(x, y, z, block) }
    }

    override fun commitEdit(onApplied: IntConsumer) {
        applied += staged.toList()
        staged.forEach { world[Triple(it.x(), it.y(), it.z())] = it.block() }
        val count = staged.size
        staged.clear()
        onApplied.accept(count)
    }

    override fun abortEdit() {
        staged.clear()
    }

    /** Returns air for everything: a test that needs real contents writes them into [world] first. */
    override fun snapshotSection(sectionX: Int, sectionY: Int, sectionZ: Int): BlockSection {
        val blocks = Array(BlockSection.BLOCK_COUNT) { Block.AIR }
        val baseX = sectionX shl 4
        val baseY = sectionY shl 4
        val baseZ = sectionZ shl 4
        for (y in 0 until BlockSection.SIZE) {
            for (z in 0 until BlockSection.SIZE) {
                for (x in 0 until BlockSection.SIZE) {
                    blocks[(y shl 8) or (z shl 4) or x] = blockAt(baseX + x, baseY + y, baseZ + z)
                }
            }
        }
        return BlockSection(sectionX, sectionY, sectionZ, blocks)
    }

    override fun sendPrefixed(message: net.kyori.adventure.text.Component) {
        prefixed += net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(message)
    }

    override fun selection(): EditSelection? = currentSelection

    override fun setSelection(selection: EditSelection?) {
        currentSelection = selection
    }

    override fun clipboard(): EditClipboard? = currentClipboard

    override fun setClipboard(clipboard: EditClipboard?) {
        currentClipboard = clipboard
    }

    override fun reportProgress(progress: EditProgress?) {
        if (progress == null) progressCleared = true else progressReports += progress
    }

    override fun message(key: String, args: Map<String, String>) {
        messages += key
    }
}
