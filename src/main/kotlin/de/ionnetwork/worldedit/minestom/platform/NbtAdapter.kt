package de.ionnetwork.worldedit.minestom.platform

import net.kyori.adventure.nbt.BinaryTag
import net.kyori.adventure.nbt.BinaryTagType
import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.ByteArrayBinaryTag
import net.kyori.adventure.nbt.ByteBinaryTag
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.DoubleBinaryTag
import net.kyori.adventure.nbt.FloatBinaryTag
import net.kyori.adventure.nbt.IntArrayBinaryTag
import net.kyori.adventure.nbt.IntBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.kyori.adventure.nbt.LongArrayBinaryTag
import net.kyori.adventure.nbt.LongBinaryTag
import net.kyori.adventure.nbt.ShortBinaryTag
import net.kyori.adventure.nbt.StringBinaryTag
import org.enginehub.linbus.common.LinTagId
import org.enginehub.linbus.tree.LinByteArrayTag
import org.enginehub.linbus.tree.LinByteTag
import org.enginehub.linbus.tree.LinCompoundTag
import org.enginehub.linbus.tree.LinDoubleTag
import org.enginehub.linbus.tree.LinFloatTag
import org.enginehub.linbus.tree.LinIntArrayTag
import org.enginehub.linbus.tree.LinIntTag
import org.enginehub.linbus.tree.LinListTag
import org.enginehub.linbus.tree.LinLongArrayTag
import org.enginehub.linbus.tree.LinLongTag
import org.enginehub.linbus.tree.LinShortTag
import org.enginehub.linbus.tree.LinStringTag
import org.enginehub.linbus.tree.LinTag
import org.enginehub.linbus.tree.LinTagType

/**
 * Converts block-entity NBT between the two libraries that happen to model it: Minestom carries
 * adventure-nbt [BinaryTag]s, WorldEdit carries lin-bus [LinTag]s.
 *
 * Structurally identical trees, so this is a straight recursive walk rather than a serialize-and-
 * reparse. That matters for correctness as much as speed: the two libraries disagree about whether a
 * root compound carries a name, so a byte-level round trip would need a format fix-up at exactly the
 * point where getting it wrong produces a silently truncated sign.
 *
 * Without this, `//copy` and `//paste` would move a sign's post and lose its text.
 */
internal object NbtAdapter {

    fun toLin(tag: CompoundBinaryTag): LinCompoundTag {
        val builder = LinCompoundTag.builder()
        for ((key, value) in tag) {
            toLin(value)?.let { builder.put(key, it) }
        }
        return builder.build()
    }

    fun toAdventure(tag: LinCompoundTag): CompoundBinaryTag {
        val builder = CompoundBinaryTag.builder()
        for ((key, value) in tag.value()) {
            toAdventure(value)?.let { builder.put(key, it) }
        }
        return builder.build()
    }

    /** `null` for a tag type neither side can represent - unreachable for real NBT, but an
     *  adventure [BinaryTag] is an open interface, so this degrades by dropping one entry rather
     *  than failing a whole paste. */
    private fun toLin(tag: BinaryTag): LinTag<*>? = when (tag) {
        is ByteBinaryTag -> LinByteTag.of(tag.value())
        is ShortBinaryTag -> LinShortTag.of(tag.value())
        is IntBinaryTag -> LinIntTag.of(tag.value())
        is LongBinaryTag -> LinLongTag.of(tag.value())
        is FloatBinaryTag -> LinFloatTag.of(tag.value())
        is DoubleBinaryTag -> LinDoubleTag.of(tag.value())
        is StringBinaryTag -> LinStringTag.of(tag.value())
        is ByteArrayBinaryTag -> LinByteArrayTag.of(*tag.value())
        is IntArrayBinaryTag -> LinIntArrayTag.of(*tag.value())
        is LongArrayBinaryTag -> LinLongArrayTag.of(*tag.value())
        is CompoundBinaryTag -> toLin(tag)
        is ListBinaryTag -> toLinList(tag)
        else -> null
    }

    /**
     * An NBT list is homogeneous and carries its element type even when empty, so the element type
     * is translated from the list's declared type rather than inferred from its first entry - an
     * empty list whose type was guessed would be written back with the wrong type byte.
     */
    private fun toLinList(tag: ListBinaryTag): LinTag<*>? {
        val elementType = linTypeOf(tag.elementType().id().toInt()) ?: return null
        val builder = LinListTag.builder(elementType)
        for (element in tag) {
            builder.add(toLin(element) ?: continue)
        }
        return builder.build()
    }

    private fun toAdventure(tag: LinTag<*>): BinaryTag? = when (tag) {
        is LinByteTag -> ByteBinaryTag.byteBinaryTag(tag.value())
        is LinShortTag -> ShortBinaryTag.shortBinaryTag(tag.value())
        is LinIntTag -> IntBinaryTag.intBinaryTag(tag.value())
        is LinLongTag -> LongBinaryTag.longBinaryTag(tag.value())
        is LinFloatTag -> FloatBinaryTag.floatBinaryTag(tag.value())
        is LinDoubleTag -> DoubleBinaryTag.doubleBinaryTag(tag.value())
        is LinStringTag -> StringBinaryTag.stringBinaryTag(tag.value())
        is LinByteArrayTag -> ByteArrayBinaryTag.byteArrayBinaryTag(*tag.value())
        is LinIntArrayTag -> IntArrayBinaryTag.intArrayBinaryTag(*tag.value())
        is LinLongArrayTag -> LongArrayBinaryTag.longArrayBinaryTag(*tag.value())
        is LinCompoundTag -> toAdventure(tag)
        is LinListTag<*> -> toAdventureList(tag)
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun toAdventureList(tag: LinListTag<*>): BinaryTag? {
        val elementType = adventureTypeOf(tag.elementType().id().id()) ?: return null
        val elements = tag.value().mapNotNull { toAdventure(it) }
        return ListBinaryTag.listBinaryTag(elementType as BinaryTagType<BinaryTag>, elements)
    }

    /** NBT type ids are the wire format's own numbering, identical on both sides, so both libraries
     *  can be asked for their own type by the same number rather than mapped case by case. */
    private fun linTypeOf(id: Int): LinTagType<LinTag<*>>? =
        runCatching { LinTagType.fromId<LinTag<*>>(LinTagId.fromId(id)) }.getOrNull()

    private fun adventureTypeOf(id: Int): BinaryTagType<out BinaryTag>? = when (id) {
        1 -> BinaryTagTypes.BYTE
        2 -> BinaryTagTypes.SHORT
        3 -> BinaryTagTypes.INT
        4 -> BinaryTagTypes.LONG
        5 -> BinaryTagTypes.FLOAT
        6 -> BinaryTagTypes.DOUBLE
        7 -> BinaryTagTypes.BYTE_ARRAY
        8 -> BinaryTagTypes.STRING
        9 -> BinaryTagTypes.LIST
        10 -> BinaryTagTypes.COMPOUND
        11 -> BinaryTagTypes.INT_ARRAY
        12 -> BinaryTagTypes.LONG_ARRAY
        else -> null
    }
}
