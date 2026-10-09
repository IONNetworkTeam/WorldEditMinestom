package de.ionnetwork.worldedit.minestom.world

import de.ionnetwork.minestomconversion.extension.edit.EditChanges
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minestom.server.instance.block.Block

/**
 * The writes of one edit, held densely.
 *
 * The obvious representation - a map from a position object to a change object - is what made the
 * per-operation block cap a memory cap: measured, it cost around 400 bytes per block, so a
 * million-block fill needed a few hundred megabytes of transient heap and the GC pauses that come
 * with it. Packing the position into a `long` and storing the block as the shared registry instance
 * it already is costs roughly 16 bytes per block instead, which is the same order as the Minestom
 * batch the edit eventually becomes. That is the whole reason a large edit is now affordable.
 *
 * Insertion order is not kept, and does not need to be: later writes to a position replace earlier
 * ones here, which is exactly what the batch would have done anyway.
 */
internal class EditBuffer : EditChanges {

    private val writes = Long2ObjectOpenHashMap<Block>()

    private var minX = Int.MAX_VALUE
    private var minY = Int.MAX_VALUE
    private var minZ = Int.MAX_VALUE
    private var maxX = Int.MIN_VALUE
    private var maxY = Int.MIN_VALUE
    private var maxZ = Int.MIN_VALUE

    /** Tracked as it grows so the per-block rules can be asked about states rather than positions,
     *  which is the difference between one question and a million. */
    private val distinct = LinkedHashSet<Block>()

    fun put(x: Int, y: Int, z: Int, block: Block) {
        writes.put(pack(x, y, z), block)
        distinct += block
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (z < minZ) minZ = z
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
        if (z > maxZ) maxZ = z
    }

    /** The block written at this position by this edit, or `null` if the edit has not touched it. */
    fun get(x: Int, y: Int, z: Int): Block? = writes.get(pack(x, y, z))

    fun isEmpty(): Boolean = writes.isEmpty()

    fun clear() {
        writes.clear()
        distinct.clear()
        minX = Int.MAX_VALUE
        minY = Int.MAX_VALUE
        minZ = Int.MAX_VALUE
        maxX = Int.MIN_VALUE
        maxY = Int.MIN_VALUE
        maxZ = Int.MIN_VALUE
    }

    override fun size(): Int = writes.size

    override fun forEach(visitor: EditChanges.Visitor) {
        val iterator = writes.long2ObjectEntrySet().fastIterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val packed = entry.longKey
            visitor.visit(unpackX(packed), unpackY(packed), unpackZ(packed), entry.value)
        }
    }

    override fun distinctBlocks(): Set<Block> = distinct

    override fun minX(): Int = minX
    override fun minY(): Int = minY
    override fun minZ(): Int = minZ
    override fun maxX(): Int = maxX
    override fun maxY(): Int = maxY
    override fun maxZ(): Int = maxZ

    internal companion object {
        /**
         * Minecraft's own block-position packing: 26 bits of x, 26 of z, 12 of y. It covers the whole
         * coordinate range a build map can have, and it is the shape everything else in the ecosystem
         * uses for the same job.
         */
        fun pack(x: Int, y: Int, z: Int): Long =
            ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)

        /** Sign-extended: a build map's coordinates are routinely negative. */
        fun unpackX(packed: Long): Int = (packed shr 38).toInt().let { signExtend(it, 26) }

        fun unpackY(packed: Long): Int = signExtend((packed and 0xFFF).toInt(), 12)

        fun unpackZ(packed: Long): Int = signExtend(((packed shl 26) shr 38).toInt(), 26)

        private fun signExtend(value: Int, bits: Int): Int {
            val shift = 32 - bits
            return (value shl shift) shr shift
        }
    }
}
