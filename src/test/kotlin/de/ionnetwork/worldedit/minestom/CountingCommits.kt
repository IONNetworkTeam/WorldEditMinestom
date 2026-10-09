package de.ionnetwork.worldedit.minestom

import de.ionnetwork.minestomconversion.extension.edit.EditTarget
import java.util.function.IntConsumer

/** Counts how many times an edit actually reaches the map, and whether it was abandoned. */
internal class CountingCommits(val delegate: FakeEditTarget = FakeEditTarget()) : EditTarget by delegate {
    var commits = 0
        private set
    var aborted = false
        private set

    override fun commitEdit(onApplied: IntConsumer) {
        commits++
        delegate.commitEdit(onApplied)
    }

    override fun abortEdit() {
        aborted = true
        delegate.abortEdit()
    }
}
