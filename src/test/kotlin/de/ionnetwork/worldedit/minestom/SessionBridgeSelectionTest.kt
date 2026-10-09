package de.ionnetwork.worldedit.minestom

import com.sk89q.worldedit.LocalSession
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.selector.limit.PermissiveSelectorLimits
import de.ionnetwork.minestomconversion.extension.edit.EditPosition
import de.ionnetwork.minestomconversion.extension.edit.EditSelection
import de.ionnetwork.worldedit.minestom.command.SessionBridge
import de.ionnetwork.worldedit.minestom.world.GuardedWorld
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `//pos1` and `//pos2` move the build server's selection, one corner at a time.
 *
 * Each command gets a selector seeded from the build server and written back after, so a corner only
 * survives to the next command if it made the round trip. Before corners crossed individually, a lone
 * `//pos1` was an incomplete region that never made it back, and `//pos1` then `//pos2` selected
 * nothing at all.
 */
class SessionBridgeSelectionTest {

    private val player = UUID.randomUUID()

    @BeforeTest
    fun setUp() {
        WorldEditTestPlatform.ensureStarted()
        SessionBridge.forget(player)
    }

    /** One command's worth of bridge: seed, let [command] touch the selector, write back. */
    private fun runCommand(target: FakeEditTarget, command: (LocalSession, GuardedWorld) -> Unit) {
        val world = GuardedWorld(target, "buildmap:${target.mapId()}")
        val session = LocalSession()
        SessionBridge.seed(player, session, world, target)
        command(session, world)
        SessionBridge.writeBack(player, session, world, target)
    }

    private fun pos1(at: BlockVector3) = { session: LocalSession, world: GuardedWorld ->
        session.getRegionSelector(world).selectPrimary(at, PermissiveSelectorLimits.getInstance())
        Unit
    }

    private fun pos2(at: BlockVector3) = { session: LocalSession, world: GuardedWorld ->
        session.getRegionSelector(world).selectSecondary(at, PermissiveSelectorLimits.getInstance())
        Unit
    }

    @Test
    fun `a lone pos1 reaches the build server`() {
        val target = FakeEditTarget()
        runCommand(target, pos1(BlockVector3.at(1, 2, 3)))

        assertEquals(EditPosition(1, 2, 3), target.corner1)
        assertNull(target.corner2)
    }

    @Test
    fun `pos1 then pos2 in separate commands makes one selection`() {
        val target = FakeEditTarget()
        runCommand(target, pos1(BlockVector3.at(1, 2, 3)))
        runCommand(target, pos2(BlockVector3.at(4, 5, 6)))

        assertEquals(EditSelection(1, 2, 3, 4, 5, 6), target.currentSelection)
    }

    @Test
    fun `pos2 completes a corner the wand set`() {
        val target = FakeEditTarget()
        target.setPositions(EditPosition(10, 64, 10), null)
        runCommand(target, pos2(BlockVector3.at(20, 70, 20)))

        assertEquals(EditSelection(10, 64, 10, 20, 70, 20), target.currentSelection)
    }

    @Test
    fun `pos1 moves one corner of an existing selection and keeps the other`() {
        val target = FakeEditTarget()
        target.setSelection(EditSelection(0, 0, 0, 5, 5, 5))
        runCommand(target, pos1(BlockVector3.at(-3, 1, 2)))

        assertEquals(EditSelection(-3, 1, 2, 5, 5, 5), target.currentSelection)
    }
}
