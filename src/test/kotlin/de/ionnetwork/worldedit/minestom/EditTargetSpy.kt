package de.ionnetwork.worldedit.minestom

import de.ionnetwork.minestomconversion.extension.edit.EditChanges
import de.ionnetwork.minestomconversion.extension.edit.EditRejection
import de.ionnetwork.minestomconversion.extension.edit.EditTarget
import net.minestom.server.instance.block.Block

/** Wraps a [FakeEditTarget] to record what the gate was actually asked about. */
internal abstract class EditTargetSpy(val delegate: FakeEditTarget) : EditTarget by delegate {

    abstract fun onCheck(blocks: List<Block>)

    override fun check(changes: EditChanges): EditRejection? {
        onCheck(changes.distinctBlocks().toList())
        return delegate.check(changes)
    }
}
