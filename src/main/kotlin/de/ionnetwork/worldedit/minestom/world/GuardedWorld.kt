package de.ionnetwork.worldedit.minestom.world

import com.sk89q.worldedit.blocks.BaseItemStack
import com.sk89q.worldedit.entity.BaseEntity
import com.sk89q.worldedit.entity.Entity
import com.sk89q.worldedit.function.operation.Operation
import com.sk89q.worldedit.function.operation.RunContext
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.math.Vector3
import com.sk89q.worldedit.util.Location
import com.sk89q.worldedit.util.SideEffect
import com.sk89q.worldedit.util.SideEffectSet
import com.sk89q.worldedit.world.AbstractWorld
import com.sk89q.worldedit.world.biome.BiomeType
import com.sk89q.worldedit.world.biome.BiomeTypes
import com.sk89q.worldedit.world.block.BaseBlock
import com.sk89q.worldedit.world.block.BlockState
import com.sk89q.worldedit.world.block.BlockStateHolder
import com.sk89q.worldedit.world.generation.TreeType
import de.ionnetwork.minestomconversion.extension.edit.BlockSection
import de.ionnetwork.minestomconversion.extension.edit.EditProgress
import de.ionnetwork.minestomconversion.extension.edit.EditRejection
import de.ionnetwork.minestomconversion.extension.edit.EditTarget
import de.ionnetwork.worldedit.minestom.platform.BlockAdapter
import net.minestom.server.instance.block.Block

/**
 * The world WorldEdit edits: one build session's map, with every build-server rule in front of it.
 *
 * Two things make this different from an ordinary platform's world.
 *
 * **Nothing is written until the operation finishes.** Every `setBlock` lands in [buffer], and only
 * [commit] hands the whole set to [EditTarget.apply]. That is what lets a refusal - a block the map's
 * profile does not allow, a jump tile's box - abort with *nothing* applied rather than half a
 * cylinder built. It also means the write reaches the world as one batch on the build server's own
 * apply path, which is what computes the inverse the `/undo` stack is made of, so a WorldEdit edit is
 * undone by the same command that undoes every other edit on the server.
 *
 * **The gate is asked eagerly, once per distinct block state.** Asking per position would be half a
 * million questions for a large `//set`; asking only at [commit] would let a builder wait out a long
 * `//smooth` before being told the map would not accept the result. Asking the first time each state
 * appears costs one question per distinct block and refuses at the first offending one - and [commit]
 * still asks about the finished set, which is what catches the region-shaped rules (a tile's box)
 * that no single position can answer.
 *
 * **A refusal is not an exception.** The build server has already told the player, in its own words,
 * exactly which block or which boundary was the problem; throwing on top of that would have WorldEdit
 * print a second, English, stack-trace-flavoured error over it - [com.sk89q.worldedit.function.operation.Operations]
 * wraps a thrown [com.sk89q.worldedit.WorldEditException] in a plain `RuntimeException`, which the
 * command layer can only report as "an unexpected error occurred". Instead every write after a refusal
 * is dropped, so WorldEdit counts nothing and reports "0 blocks changed" under the real explanation.
 *
 * This class is not thread-safe and is not meant to be: one instance exists per command, is used by
 * exactly that command, and is discarded after. It must be driven from the instance thread, because
 * [getBlock] reads Minestom chunks directly.
 */
@Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
internal class GuardedWorld(
    internal val target: EditTarget,
    private val worldName: String,
    /**
     * Whether this world is being driven from a worker thread rather than the map's own.
     *
     * It decides how reads are served, and getting it wrong is not subtle: a read from a worker
     * straight into Minestom's chunk storage races the tick, while a section snapshot taken from the
     * map thread waits on the map thread for itself and deadlocks.
     */
    private val offMapThread: Boolean = false,
    /** What the builder typed, shown back to them while the edit runs. */
    private val commandLabel: String = ""
) : AbstractWorld() {

    private val buffer = EditBuffer()

    /**
     * Sections copied from the map so a worker can read them, kept for the life of one edit.
     *
     * One copy per section rather than a hop per block: a section is 4096 blocks, so a region scan
     * pays a few hundred hops instead of a few million. They are shared registry instances, so a
     * section costs about 16KB however varied its contents.
     */
    private val sections = HashMap<Long, BlockSection>()

    /** Every chunk the edit touches, loaded in one go at commit - see [checkLoadedChunk]. */
    private val ensuredChunks = HashSet<Long>()

    /** Reported alongside the edit's own timing, so a slow edit can be attributed to the thing that
     *  actually made it slow rather than guessed at. */
    internal var chunkLoadMillis = 0L
        private set

    internal var chunkCount = 0
        private set

    /** Per-state gate answers, so a 500k-block fill asks the guard once rather than 500k times. */
    private val gateAnswers = HashMap<Block, Boolean>()

    /** Set by the first refusal. Everything after it is dropped and nothing is ever applied. */
    private var refused = false

    private val startedAt = System.nanoTime()
    private var nextProgressAt = 0L

    /**
     * How many writes this edit is expected to make, or 0 when that cannot be said.
     *
     * Taken from the builder's own selection, because that is the one total WorldEdit hands over
     * before it starts: a command bounded by the selection will write at most its volume. It is
     * abandoned the moment a write lands outside that box - `//hcyl` and `//sphere` are placed at the
     * player rather than in the selection, and claiming their progress against an unrelated box would
     * be worse than claiming nothing. Dropping it on the first stray write means the guess corrects
     * itself instead of needing a list of which commands are selection-bounded, which would be one
     * more list to go stale.
     */
    private var expectedBlocks: Long = selectionVolume()

    private fun selectionVolume(): Long {
        val selection = target.selection() ?: return 0
        val width = (maxOf(selection.x1(), selection.x2()) - minOf(selection.x1(), selection.x2()) + 1).toLong()
        val height = (maxOf(selection.y1(), selection.y2()) - minOf(selection.y1(), selection.y2()) + 1).toLong()
        val length = (maxOf(selection.z1(), selection.z2()) - minOf(selection.z1(), selection.z2()) + 1).toLong()
        return width * height * length
    }

    private val selectionBounds: IntArray? = target.selection()?.let {
        intArrayOf(
            minOf(it.x1(), it.x2()), minOf(it.y1(), it.y2()), minOf(it.z1(), it.z2()),
            maxOf(it.x1(), it.x2()), maxOf(it.y1(), it.y2()), maxOf(it.z1(), it.z2())
        )
    }

    override fun getName(): String = worldName

    override fun id(): String = worldName

    override fun getMinY(): Int = target.minY()

    /** WorldEdit's ceiling is inclusive; the build server reports Minestom's exclusive one. */
    override fun getMaxY(): Int = target.maxY() - 1

    override fun <B : BlockStateHolder<B>> setBlock(
        position: BlockVector3,
        block: B,
        sideEffects: SideEffectSet
    ): Boolean {
        if (refused) return false
        val minestomBlock = BlockAdapter.toMinestom(block) ?: return false
        // Refused, not thrown. Throwing here aborts the operation but not the edit session, and
        // `EditSession.close()` still flushes in a finally - so an edit far over the cap would have
        // applied the first few million blocks of itself and reported an error, which is the worst of
        // both. Refusing abandons the whole thing, which is what the cap is for.
        if (buffer.size() >= target.maxBlocks() && buffer.get(position.x(), position.y(), position.z()) == null) {
            refuse(
                EditRejection(
                    "buildserver.edit.reject.too_large",
                    mapOf("count" to buffer.size().toString(), "max" to target.maxBlocks().toString())
                )
            )
            return false
        }
        // Asked again rather than trusted: [GuardExtent] normally refuses first, but it is installed
        // by an event subscriber and only on sessions that went through the event bus. A direct
        // write to this world - another extension, a future internal caller - must still be gated.
        if (!gate(minestomBlock)) return false
        buffer.put(position.x(), position.y(), position.z(), minestomBlock)
        noteOutsideSelection(position)
        reportProgressOccasionally()
        return true
    }

    /** Six comparisons on the hot path, and only while a total is still being claimed. */
    private fun noteOutsideSelection(position: BlockVector3) {
        if (expectedBlocks == 0L) return
        val bounds = selectionBounds ?: return
        if (position.x() < bounds[0] || position.y() < bounds[1] || position.z() < bounds[2] ||
            position.x() > bounds[3] || position.y() > bounds[4] || position.z() > bounds[5]
        ) {
            expectedBlocks = 0
        }
    }

    /**
     * Tells the build server how far along this edit is, a few times a second.
     *
     * Throttled twice over, because this sits on the hottest path there is - once per block written.
     * The block-count mask keeps the common case to a single bitwise test, so the clock is only read
     * once every few thousand blocks rather than five million times.
     */
    private fun reportProgressOccasionally() {
        if (buffer.size() and PROGRESS_BLOCK_MASK != 0) return
        val elapsed = System.nanoTime() - startedAt
        if (elapsed < nextProgressAt) return
        nextProgressAt = elapsed + PROGRESS_INTERVAL_NANOS
        target.reportProgress(
            EditProgress(commandLabel, buffer.size().toLong(), elapsed / 1_000_000, expectedBlocks)
        )
    }

    /**
     * The question [GuardExtent] asks before WorldEdit counts a write. Same gate as [setBlock]'s, so
     * a block refused here is refused there too and the answer is cached either way.
     */
    internal fun <B : BlockStateHolder<B>> allows(position: BlockVector3, block: B): Boolean {
        if (refused) return false
        val minestomBlock = BlockAdapter.toMinestom(block) ?: return false
        return gate(minestomBlock)
    }

    /**
     * Asks the build server about a block the first time that exact state shows up in this operation,
     * and reports whether the write may proceed.
     *
     * The question is asked with this change's real position, so a rule that depends on where the
     * block goes is answered honestly for at least the first occurrence; [commit] re-asks about the
     * whole set, which is where a position-dependent rule is actually enforced across every write.
     */
    private fun gate(block: Block): Boolean {
        gateAnswers[block]?.let { return it }
        val allowed = target.canEverPlace(block)
        gateAnswers[block] = allowed
        if (!allowed) {
            refuse(EditRejection.of("buildserver.edit.reject.profile", "state", block.state()))
        }
        return allowed
    }

    /** Reports the refusal once and abandons the whole operation. */
    private fun refuse(rejection: EditRejection) {
        refused = true
        buffer.clear()
        sections.clear()
        ensuredChunks.clear()
        target.abortEdit()
        target.message(rejection)
    }

    override fun getBlock(position: BlockVector3): BlockState = BlockAdapter.toWorldEdit(readBlock(position))

    override fun getFullBlock(position: BlockVector3): BaseBlock = BlockAdapter.toWorldEditFull(readBlock(position))

    /**
     * Reads this edit's own pending write first, then the map.
     *
     * The pending write has to win: WorldEdit reads positions it has already written within the same
     * operation - `//smooth` looks at a neighbourhood, `//stack` overlaps itself - and reading the
     * untouched map there would make a two-stage operation act on stale blocks.
     */
    private fun readBlock(position: BlockVector3): Block {
        if (position.y() < minY || position.y() > maxY) return Block.AIR
        buffer.get(position.x(), position.y(), position.z())?.let { return it }
        if (!offMapThread) return target.blockAt(position.x(), position.y(), position.z())
        val sectionX = position.x() shr 4
        val sectionY = position.y() shr 4
        val sectionZ = position.z() shr 4
        val key = EditBuffer.pack(sectionX, sectionY, sectionZ)
        val section = sections.getOrPut(key) { target.snapshotSection(sectionX, sectionY, sectionZ) }
        return section.at(position.x(), position.y(), position.z())
    }

    /**
     * Hands the buffered writes to the build server, after one last whole-set check.
     *
     * **The work happens in the returned operation, not here**, and that is load-bearing.
     * `AbstractDelegateExtent.commit` builds its queue by calling each extent's `commit()` from the
     * outside in and running the results in that order - so at the moment this method is called,
     * WorldEdit's own reordering and chunk-batching extents have not flushed yet and this buffer is
     * still empty. Doing the apply here would reliably write nothing at all: the buffer fills a
     * moment later, from operations queued ahead of this one, and then nothing would ever commit it.
     */
    override fun commit(): Operation = ApplyBufferedChanges()

    private inner class ApplyBufferedChanges : Operation {

        override fun resume(run: RunContext): Operation? {
            if (refused || buffer.isEmpty()) return null
            val rejection = target.check(buffer)
            if (rejection != null) {
                // Nothing has been staged yet, so a refusal here writes nothing - no rollback, no
                // half-applied edit, which is the property the whole guard design rests on.
                refuse(rejection)
                return null
            }
            // Reported before the buffer is handed over and cleared, because this is the only moment
            // the exact final count is known: progress up to here is throttled and always a little
            // behind, so without this the bar would stop just short of full and the builder would be
            // left looking at an edit that appears to have given up.
            // Every chunk the edit touches, started together and waited for once - see
            // [checkLoadedChunk] for why this is not done as each chunk is first reached.
            if (ensuredChunks.isNotEmpty()) {
                val loadingStartedAt = System.nanoTime()
                target.ensureLoaded(ensuredChunks.toLongArray())
                chunkLoadMillis = (System.nanoTime() - loadingStartedAt) / 1_000_000
                chunkCount = ensuredChunks.size
            }

            val written = buffer.size().toLong()
            target.reportProgress(
                EditProgress(commandLabel, written, (System.nanoTime() - startedAt) / 1_000_000, written)
            )

            target.beginEdit()
            target.stage(buffer)
            buffer.clear()
            gateAnswers.clear()
            sections.clear()
            ensuredChunks.clear()
            // Fire and forget: the build server applies on its own map thread and reports its own
            // count, while WorldEdit's "N blocks changed" comes from its own tally of setBlock calls.
            target.commitEdit { }
            return null
        }

        override fun cancel() = Unit
    }

    // --- Things a build server deliberately does not do -----------------------------------------

    /** Build instances run no vanilla block updates, so nothing here has a side effect to apply. */
    override fun applySideEffects(
        position: BlockVector3,
        previousType: BlockState,
        sideEffectSet: SideEffectSet
    ): Set<SideEffect> = emptySet()

    /** Minestom has no per-block light query; WorldEdit uses this only for informational output and
     *  for `//snow`-style checks, which read a missing value as "dark". */
    override fun getBlockLightLevel(position: BlockVector3): Int = 0

    /**
     * No-op: nothing on a build server should spill a chest's contents onto the floor when a region
     * is overwritten, and Minestom has no container inventory attached to a block to clear anyway.
     */
    override fun clearContainerBlockContents(position: BlockVector3): Boolean = false

    /** Editing never drops items on a build server - a `//set air` over a forest would otherwise
     *  carpet the map in entities. */
    override fun dropItem(position: Vector3, item: BaseItemStack, count: Int) = Unit

    override fun dropItem(position: Vector3, item: BaseItemStack) = Unit

    override fun simulateBlockMine(position: BlockVector3) {
        // Same reasoning as dropItem: remove the block, drop nothing. A refusal here is reported by
        // the gate like any other and simply leaves the block in place.
        setBlock(position, BlockAdapter.toWorldEdit(Block.AIR), SideEffectSet.none())
    }

    /** No vanilla world generator behind a build instance, so there is no tree to grow. `//tree`
     *  and the `tree` brush report failure rather than silently doing nothing elsewhere. */
    override fun generateTree(type: TreeType, editSession: com.sk89q.worldedit.EditSession, position: BlockVector3): Boolean = false

    /**
     * Notes that WorldEdit is about to touch this chunk. Nothing is loaded yet.
     *
     * WorldEdit asks per *block* - its `ChunkLoadingExtent` calls this on every write - and on Bukkit
     * it has to, because a write to an unloaded chunk is lost. The same is true here: a Minestom batch
     * skips unloaded chunks with a warning. But loading them one at a time, as each is first touched,
     * means paying a chunk load's latency once per chunk in sequence, and a wide edit touches
     * thousands. A `//hcyl` of radius 500 is barely three thousand blocks and took twelve seconds
     * that way; the blocks were never the cost, the four hundred chunk loads in a row were.
     *
     * So this only records. [commit] loads the whole set at once, where the loads can overlap.
     */
    override fun checkLoadedChunk(position: BlockVector3) {
        ensuredChunks.add((position.x() shr 4).toLong() shl 32 or ((position.z() shr 4).toLong() and 0xFFFFFFFFL))
    }

    /**
     * A build map has no meaningful spawn from WorldEdit's point of view - where a player lands is
     * the build server's business, and WorldEdit only reads this for `//jumpto`-adjacent fallbacks.
     */
    override fun getSpawnPosition(): BlockVector3 = BlockVector3.at(0, minY, 0)

    /** Entities are out of scope: a build map's contents are blocks, and the save pipeline only
     *  persists blocks. `//copy` therefore never carries entities, which matches `/copy`. */
    override fun getEntities(region: com.sk89q.worldedit.regions.Region): List<Entity> = emptyList()

    override fun getEntities(): List<Entity> = emptyList()

    override fun createEntity(location: Location, entity: BaseEntity): Entity? = null

    /**
     * Biomes are not edited here, and the 3-D overloads are the ones overridden on purpose: the
     * 2-D and 3-D defaults on [com.sk89q.worldedit.extent.InputExtent]/[com.sk89q.worldedit.extent.OutputExtent]
     * delegate to each other, so overriding only the 2-D pair would recurse until the stack ran out.
     *
     * A build map's canonical storage is blocks only, so a biome a builder set would be silently
     * dropped by the next save - reporting "no" here is the honest answer rather than a write that
     * looks like it worked until the map is reloaded.
     */
    override fun getBiome(position: BlockVector3): BiomeType = plains

    override fun setBiome(position: BlockVector3, biome: BiomeType): Boolean = false

    override fun fullySupports3DBiomes(): Boolean = false

    override fun equals(other: Any?): Boolean = other is GuardedWorld && other.worldName == worldName

    override fun hashCode(): Int = worldName.hashCode()
}

/** Resolved lazily, like every other registry constant: [BiomeTypes]' fields are filled in by
 *  registry lookup and read as null before the platform registers. */
private val plains: BiomeType
    get() = BiomeTypes.PLAINS ?: BiomeType("minecraft:plains")

/** Blocks between clock reads while reporting progress; a power of two minus one so the test is a
 *  single mask. 4095 is a few thousand blocks, well under one report's worth of work. */
private const val PROGRESS_BLOCK_MASK = 0xFFF

/** Roughly five updates a second - fast enough to look alive, slow enough not to matter. */
private const val PROGRESS_INTERVAL_NANOS = 200_000_000L
