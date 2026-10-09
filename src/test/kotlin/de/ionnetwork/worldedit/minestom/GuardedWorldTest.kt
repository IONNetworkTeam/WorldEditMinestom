package de.ionnetwork.worldedit.minestom

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import de.ionnetwork.worldedit.minestom.platform.BlockAdapter
import de.ionnetwork.minestomconversion.extension.edit.EditSelection
import de.ionnetwork.worldedit.minestom.world.GuardedWorld
import net.minestom.server.instance.block.Block
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What actually happens to a build map when a WorldEdit command runs.
 *
 * These drive real [EditSession]s - the same objects `//set` and `//hcyl` drive - against a
 * [FakeEditTarget], so the assertions are about applied blocks rather than about the plumbing that
 * would have applied them.
 */
class GuardedWorldTest {

    @BeforeTest
    fun boot() {
        WorldEditTestPlatform.ensureStarted()
    }

    private fun session(world: GuardedWorld): EditSession =
        WorldEdit.getInstance().newEditSessionBuilder().world(world).maxBlocks(500_000).build()

    /** `//set stone` over a 3x3x3 box. */
    @Test
    fun `a fill applies every block in the region`() {
        val target = FakeEditTarget()
        val world = GuardedWorld(target, "map")
        val region = CuboidRegion(world, BlockVector3.at(0, 0, 0), BlockVector3.at(2, 2, 2))

        session(world).use { it.setBlocks(region, BlockAdapter.toWorldEdit(Block.STONE)) }

        assertEquals(27, target.appliedChanges.size)
        assertTrue(target.appliedChanges.all { it.block() == Block.STONE })
        assertEquals(Block.STONE, target.blockAt(1, 1, 1))
    }

    /**
     * The single most important property of the whole design: a refused block leaves the map
     * untouched, not partly built. The refusal is reported once, in the build server's own words,
     * and WorldEdit reports zero blocks changed rather than an error on top of it.
     */
    @Test
    fun `a refused block applies nothing at all`() {
        val target = FakeEditTarget(rejectBlock = { it == Block.BEACON })
        val world = GuardedWorld(target, "map")
        val region = CuboidRegion(world, BlockVector3.at(0, 0, 0), BlockVector3.at(9, 9, 9))

        val changed = session(world).use { it.setBlocks(region, BlockAdapter.toWorldEdit(Block.BEACON)) }

        assertEquals(0, changed, "WorldEdit should count nothing when every write was refused")
        assertEquals(emptyList(), target.applied, "a refused edit must write nothing")
        assertEquals(listOf("buildserver.edit.reject.profile"), target.messages)
    }

    /** One refusal, not one per block: the message is sent once however big the operation was. */
    @Test
    fun `a refusal is reported exactly once`() {
        val target = FakeEditTarget(rejectBlock = { it == Block.BEACON })
        val world = GuardedWorld(target, "map")
        val region = CuboidRegion(world, BlockVector3.at(0, 0, 0), BlockVector3.at(19, 19, 19))

        session(world).use { it.setBlocks(region, BlockAdapter.toWorldEdit(Block.BEACON)) }

        assertEquals(1, target.messages.size, "sent ${target.messages.size} refusals for one operation")
    }

    /**
     * The gate is asked once per distinct block state, not once per position - otherwise a
     * half-million-block fill would ask the build server half a million questions. A refusal on the
     * very first block still stops the whole operation.
     */
    @Test
    fun `a large fill asks the guard once per distinct block`() {
        val asked = mutableListOf<Block>()
        val target = object : EditTargetSpy(FakeEditTarget()) {
            override fun onCheck(blocks: List<Block>) {
                asked += blocks
            }
        }
        val world = GuardedWorld(target, "map")
        val region = CuboidRegion(world, BlockVector3.at(0, 0, 0), BlockVector3.at(19, 19, 19))

        session(world).use { it.setBlocks(region, BlockAdapter.toWorldEdit(Block.STONE)) }

        // 8000 positions, one per-block gate question plus the one whole-set check at commit.
        assertEquals(8000, target.delegate.appliedChanges.size)
        assertTrue(asked.size < 10, "asked the guard ${asked.size} times for one block state")
    }

    /** `//hcyl stone 3 4` - proof that WorldEdit's generation commands reach the map, not just fills. */
    @Test
    fun `a hollow cylinder writes only its shell`() {
        val target = FakeEditTarget()
        val world = GuardedWorld(target, "map")

        val changed = session(world).use {
            it.makeCylinder(BlockVector3.at(0, 0, 0), BlockAdapter.toWorldEdit(Block.STONE), 3.0, 4, false)
        }

        assertTrue(changed > 0, "the cylinder changed nothing")
        // <=, not ==: the buffer is keyed by position, so a generator that writes the same rim
        // position twice (WorldEdit's cylinder does, at the axis crossings) collapses to one write.
        assertTrue(
            target.appliedChanges.size in 1..changed,
            "applied ${target.appliedChanges.size} of $changed writes"
        )
        // Hollow: the axis column is untouched above the first layer's centre check.
        assertEquals(Block.AIR, target.blockAt(0, 2, 0))
        // Shell: the rim is stone.
        assertEquals(Block.STONE, target.blockAt(3, 2, 0))
    }

    /**
     * A region rule (a jump tile's box) is a property of the whole change set, not of any one block,
     * so no per-block gate can catch it - it is the check at commit that does, and it still has to
     * leave the map untouched.
     */
    @Test
    fun `an edit leaving the allowed region applies nothing`() {
        val target = FakeEditTarget(bounds = 0..7)
        val world = GuardedWorld(target, "map")
        val region = CuboidRegion(world, BlockVector3.at(0, 0, 0), BlockVector3.at(20, 2, 2))

        session(world).use { it.setBlocks(region, BlockAdapter.toWorldEdit(Block.STONE)) }

        assertEquals(emptyList(), target.applied)
        assertEquals(listOf("buildserver.error.tile_out_of_bounds"), target.messages)
    }

    /**
     * A buffered write has to read back as itself. WorldEdit layers its own caches on top, but the
     * world underneath is what a `//replace` ultimately consults, and a buffer that read through to
     * the unmodified map would make a two-stage operation act on stale blocks.
     */
    @Test
    fun `a read sees a write already buffered`() {
        val target = FakeEditTarget()
        val world = GuardedWorld(target, "map")

        world.setBlock(BlockVector3.at(1, 1, 1), BlockAdapter.toWorldEdit(Block.STONE))

        assertEquals(Block.STONE, BlockAdapter.toMinestom(world.getBlock(BlockVector3.at(1, 1, 1))))
        assertEquals(Block.AIR, target.blockAt(1, 1, 1), "nothing should be applied before commit")
    }

    /**
     * An edit reaches the map exactly once, however much WorldEdit wrote. That is what keeps a
     * multi-million-block `//set` to one chunk update and, more importantly, one entry in the undo
     * history - `/undo` should reverse a `//set`, not a slice of one.
     */
    @Test
    fun `a whole edit lands as a single commit`() {
        val target = CountingCommits()
        val world = GuardedWorld(target, "map")
        val region = CuboidRegion(world, BlockVector3.ZERO, BlockVector3.at(19, 19, 19))

        session(world).use { it.setBlocks(region, BlockAdapter.toWorldEdit(Block.STONE)) }

        assertEquals(8000, target.delegate.appliedChanges.size)
        assertEquals(1, target.commits, "an edit should commit once, not once per slice")
    }

    /** A refusal must not even stage: nothing reaches the map, so there is nothing to roll back. */
    @Test
    fun `a refused edit is abandoned, never committed`() {
        val target = CountingCommits(FakeEditTarget(bounds = 0..7))
        val world = GuardedWorld(target, "map")
        val region = CuboidRegion(world, BlockVector3.ZERO, BlockVector3.at(20, 2, 2))

        session(world).use { it.setBlocks(region, BlockAdapter.toWorldEdit(Block.STONE)) }

        assertEquals(0, target.commits, "a refused edit committed anyway")
        assertTrue(target.aborted, "a refused edit should abandon what it staged")
        assertEquals(emptyList(), target.delegate.applied)
    }

    /**
     * Driven from a worker thread, reads come from section copies rather than the live map - that is
     * what lets the server keep ticking through a large edit. The edit's own pending writes still
     * have to win over the copy, or a two-stage operation acts on stale blocks.
     */
    @Test
    fun `an off-thread world reads through section snapshots`() {
        val target = FakeEditTarget()
        target.world[Triple(3, 4, 5)] = Block.DIRT
        val world = GuardedWorld(target, "map", offMapThread = true)

        assertEquals(Block.DIRT, BlockAdapter.toMinestom(world.getBlock(BlockVector3.at(3, 4, 5))))

        world.setBlock(BlockVector3.at(3, 4, 5), BlockAdapter.toWorldEdit(Block.STONE))
        assertEquals(
            Block.STONE,
            BlockAdapter.toMinestom(world.getBlock(BlockVector3.at(3, 4, 5))),
            "a pending write must win over the snapshot"
        )
    }

    /**
     * An edit past the cap must write nothing, not the first few million blocks of itself.
     *
     * Throwing at the cap - which is the obvious thing, and what this did first - aborts the
     * operation but not the edit session, and `EditSession.close()` flushes in a `finally`. So a
     * `//cyl stone 500 500`, which is around 400 million blocks, would report an error and still
     * apply five million of them.
     */
    @Test
    fun `an edit past the cap applies nothing`() {
        val target = CountingCommits(FakeEditTarget(maxBlocks = 1000))
        val world = GuardedWorld(target, "map")
        val region = CuboidRegion(world, BlockVector3.ZERO, BlockVector3.at(29, 29, 29))

        session(world).use { it.setBlocks(region, BlockAdapter.toWorldEdit(Block.STONE)) }

        assertEquals(0, target.commits, "an over-cap edit committed part of itself")
        assertEquals(emptyList(), target.delegate.applied)
        assertTrue(
            target.delegate.messages.contains("buildserver.edit.reject.too_large"),
            "the builder should be told why, got ${target.delegate.messages}"
        )
    }

    /**
     * Every chunk an edit touches is loaded in one request, at the end.
     *
     * WorldEdit asks per block, and loading as each chunk is first reached means paying a load's
     * latency once per chunk in sequence - which is what made a three-thousand-block `//hcyl` take
     * twelve seconds. The blocks were never the cost; four hundred serial chunk loads were.
     */
    @Test
    fun `every chunk is loaded in one request, not one at a time`() {
        val target = FakeEditTarget()
        val world = GuardedWorld(target, "map", offMapThread = true)
        val region = CuboidRegion(world, BlockVector3.ZERO, BlockVector3.at(31, 15, 31))

        session(world).use { it.setBlocks(region, BlockAdapter.toWorldEdit(Block.STONE)) }

        // 32x32 blocks across x/z is four chunks, from 16384 writes.
        assertEquals(4, target.loadedChunks.size, "loaded ${target.loadedChunks.size} chunks")
        assertEquals(1, target.ensureLoadedCalls, "chunks were loaded in ${target.ensureLoadedCalls} rounds")
    }

    /** A refused edit should not have loaded anything: it never reaches the commit. */
    @Test
    fun `a refused edit loads no chunks at all`() {
        val target = FakeEditTarget(rejectBlock = { it == Block.BEACON })
        val world = GuardedWorld(target, "map", offMapThread = true)
        val region = CuboidRegion(world, BlockVector3.ZERO, BlockVector3.at(31, 15, 31))

        session(world).use { it.setBlocks(region, BlockAdapter.toWorldEdit(Block.BEACON)) }

        assertEquals(0, target.ensureLoadedCalls)
    }

    /**
     * A long edit has to say it is alive. Now that edits run in the background, a builder otherwise
     * sees nothing at all until the world changes, which is indistinguishable from a dropped command.
     */
    @Test
    fun `a long edit reports progress as it goes`() {
        val target = FakeEditTarget()
        val world = GuardedWorld(target, "map", offMapThread = true, commandLabel = "//set stone")
        val region = CuboidRegion(world, BlockVector3.ZERO, BlockVector3.at(39, 39, 39))

        session(world).use { it.setBlocks(region, BlockAdapter.toWorldEdit(Block.STONE)) }

        // Exactly how many reports arrive depends on how fast the machine is - the throttle is a
        // wall-clock interval, and an edit this size can finish inside one of them. What is pinned is
        // that a running edit says something, labelled with what the builder typed and counting real
        // blocks; the throttle itself is pinned by the test below.
        assertTrue(target.progressReports.isNotEmpty(), "a 64000-block edit reported no progress")
        val first = target.progressReports.first()
        assertEquals("//set stone", first.command())
        assertTrue(first.blocks() > 0, "progress reported zero blocks")
        assertTrue(
            first.blocks() <= 64000,
            "progress reported ${first.blocks()} blocks for a 64000-block edit"
        )
    }

    /**
     * Progress is reported from the hottest path there is - once per block - so it is throttled hard.
     * Reporting per block would cost more than the edit.
     */
    @Test
    fun `progress is throttled, not reported per block`() {
        val target = FakeEditTarget()
        val world = GuardedWorld(target, "map", offMapThread = true, commandLabel = "//set stone")
        val region = CuboidRegion(world, BlockVector3.ZERO, BlockVector3.at(39, 39, 39))

        session(world).use { it.setBlocks(region, BlockAdapter.toWorldEdit(Block.STONE)) }

        assertTrue(
            target.progressReports.size < 100,
            "64000 blocks produced ${target.progressReports.size} progress reports"
        )
    }

    /**
     * A selection-bounded command has a real total, which is what makes a time estimate possible at
     * all. WorldEdit never announces one, but a command working inside the builder's own selection
     * cannot write more blocks than that selection holds.
     */
    @Test
    fun `an edit inside the selection reports a total to estimate against`() {
        val target = FakeEditTarget()
        target.setSelection(EditSelection(0, 0, 0, 19, 19, 19))
        val world = GuardedWorld(target, "map", offMapThread = true, commandLabel = "//set stone")
        val region = CuboidRegion(world, BlockVector3.ZERO, BlockVector3.at(19, 19, 19))

        session(world).use { it.setBlocks(region, BlockAdapter.toWorldEdit(Block.STONE)) }

        val report = target.progressReports.firstOrNull() ?: error("no progress reported")
        assertEquals(8000, report.totalBlocks(), "the selection's volume should be the total")
        assertTrue(report.hasTotal())
    }

    /**
     * An edit that leaves the selection has no business being measured against it - `//hcyl` and
     * `//sphere` are placed at the player, not in the box. Dropping the total on the first stray
     * write means the guess corrects itself, rather than needing a list of which commands are
     * selection-bounded to keep up to date.
     */
    @Test
    fun `an edit that leaves the selection stops claiming a total`() {
        val target = FakeEditTarget()
        target.setSelection(EditSelection(0, 0, 0, 4, 4, 4))
        val world = GuardedWorld(target, "map", offMapThread = true, commandLabel = "//sphere stone 20")
        // Deliberately outside the little selection, the way a sphere at the player would be.
        val region = CuboidRegion(world, BlockVector3.at(100, 0, 100), BlockVector3.at(139, 19, 139))

        session(world).use { it.setBlocks(region, BlockAdapter.toWorldEdit(Block.STONE)) }

        val report = target.progressReports.firstOrNull() ?: error("no progress reported")
        assertEquals(0, report.totalBlocks(), "a stray edit kept claiming the selection's total")
        assertTrue(!report.hasTotal())
    }

    /**
     * The last thing a finished edit reports is its exact count, as a complete total. Progress up to
     * that point is throttled and always a little behind, so without it the bar would stop just short
     * of full - and a bar that stops short reads as an edit that gave up.
     */
    @Test
    fun `a finished edit reports itself complete`() {
        val target = FakeEditTarget()
        target.setSelection(EditSelection(0, 0, 0, 19, 19, 19))
        val world = GuardedWorld(target, "map", offMapThread = true, commandLabel = "//set stone")
        val region = CuboidRegion(world, BlockVector3.ZERO, BlockVector3.at(19, 19, 19))

        session(world).use { it.setBlocks(region, BlockAdapter.toWorldEdit(Block.STONE)) }

        val last = target.progressReports.last()
        assertEquals(8000, last.blocks())
        assertEquals(8000, last.totalBlocks())
        assertTrue(last.hasTotal(), "the final report should read as complete")
    }

    /** A refused edit must not report itself complete - nothing was written. */
    @Test
    fun `a refused edit never reports itself complete`() {
        val target = FakeEditTarget(bounds = 0..7)
        target.setSelection(EditSelection(0, 0, 0, 20, 2, 2))
        val world = GuardedWorld(target, "map", offMapThread = true, commandLabel = "//set stone")
        val region = CuboidRegion(world, BlockVector3.ZERO, BlockVector3.at(20, 2, 2))

        session(world).use { it.setBlocks(region, BlockAdapter.toWorldEdit(Block.STONE)) }

        assertTrue(
            target.progressReports.none { it.hasTotal() && it.blocks() >= it.totalBlocks() },
            "a refused edit reported itself complete"
        )
    }

    @Test
    fun `reading outside the world height is air, not a crash`() {
        val target = FakeEditTarget()
        val world = GuardedWorld(target, "map")
        assertEquals(Block.AIR, BlockAdapter.toMinestom(world.getBlock(BlockVector3.at(0, 5000, 0))))
        assertEquals(Block.AIR, BlockAdapter.toMinestom(world.getBlock(BlockVector3.at(0, -5000, 0))))
    }
}
