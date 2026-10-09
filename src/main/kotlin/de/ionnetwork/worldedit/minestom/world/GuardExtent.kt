package de.ionnetwork.worldedit.minestom.world

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.event.extent.EditSessionEvent
import com.sk89q.worldedit.extent.AbstractDelegateExtent
import com.sk89q.worldedit.extent.Extent
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.session.request.Request
import com.sk89q.worldedit.util.eventbus.Subscribe
import com.sk89q.worldedit.world.block.BlockStateHolder

/**
 * Refuses a disallowed block at the very top of WorldEdit's extent stack, before anything counts it.
 *
 * [GuardedWorld] could refuse the same write on its own - it is the last extent in the chain - but by
 * then WorldEdit has already counted it. Its buffering and reordering extents accept a write
 * immediately and flush much later, so `//set` would report "1000 blocks changed" next to the message
 * saying the block was not allowed and nothing had been written. Sitting above those extents means a
 * refusal is a refusal all the way up: `EditSession` never increments, and the player is told zero.
 *
 * This is WorldEdit's own intended seam for exactly this - it is where WorldGuard puts its region
 * protection on Bukkit - rather than a hook invented here.
 */
internal class GuardExtent(parent: Extent, private val world: GuardedWorld) : AbstractDelegateExtent(parent) {

    private var historyDisabled = false

    override fun <B : BlockStateHolder<B>> setBlock(location: BlockVector3, block: B): Boolean {
        if (!world.allows(location, block)) return false
        disableWorldEditHistoryOnce()
        return super.setBlock(location, block)
    }

    /**
     * Turns off WorldEdit's own change set, once, on the first write of the edit.
     *
     * WorldEdit records the block before and after every single write so that its `//undo` can put
     * them back. Nothing here uses that: the build server owns undo, through the inverse of the batch
     * the edit is applied as, which is what lets `/undo` reverse a `//set` and a `/bs migrate` alike.
     * Leaving it on costs a read on every write and tens of bytes per block for a history no command
     * can reach - on a large edit that is the single largest allocation in the whole operation, and
     * it is pure waste.
     *
     * Done here rather than at construction because the session does not exist yet when WorldEdit
     * posts the event this extent is installed from; it reaches the request's thread-local only once
     * the command is running, which the first write guarantees.
     */
    private fun disableWorldEditHistoryOnce() {
        if (historyDisabled) return
        historyDisabled = true
        runCatching { Request.request().editSession?.setTrackingHistory(false) }
    }
}

/**
 * Installs [GuardExtent] on every edit session that targets a build map.
 *
 * Registered on WorldEdit's event bus once at startup. Sessions that do not target a [GuardedWorld]
 * are left alone - there are none on a build node today, but a null world is how WorldEdit builds a
 * clipboard-only session, and that has nothing to guard.
 */
internal object GuardExtentListener {

    /**
     * [EditSession.Stage.BEFORE_HISTORY] is the outermost of
     * the three stages - above the change set, the mask and the block-change limiter - which is what
     * makes a refusal here invisible to the counter and to the undo history alike. A refused write
     * should not appear in either.
     */
    @Subscribe
    fun onEditSession(event: EditSessionEvent) {
        if (event.stage != EditSession.Stage.BEFORE_HISTORY) return
        val world = event.world as? GuardedWorld ?: return
        event.extent = GuardExtent(event.extent, world)
    }
}
