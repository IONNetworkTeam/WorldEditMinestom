package de.ionnetwork.worldedit.minestom.actor

import com.sk89q.worldedit.blocks.BaseItemStack
import com.sk89q.worldedit.entity.BaseEntity
import com.sk89q.worldedit.extension.platform.AbstractPlayerActor
import com.sk89q.worldedit.extent.inventory.BlockBag
import com.sk89q.worldedit.internal.cui.CUIEvent
import com.sk89q.worldedit.math.Vector3
import com.sk89q.worldedit.session.SessionKey
import com.sk89q.worldedit.util.HandSide
import com.sk89q.worldedit.util.Location
import com.sk89q.worldedit.util.formatting.text.Component
import com.sk89q.worldedit.util.formatting.text.TextComponent
import com.sk89q.worldedit.util.formatting.text.format.TextColor
import com.sk89q.worldedit.world.World
import com.sk89q.worldedit.world.item.ItemType
import de.ionnetwork.worldedit.minestom.world.GuardedWorld
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerHand
import java.util.Locale
import java.util.UUID

/**
 * One player, as WorldEdit sees them, for the duration of one command.
 *
 * Built per command rather than cached, because [world] must be the *same* [GuardedWorld] instance
 * throughout: that world buffers every write and applies it once at the end, so an actor that handed
 * out a fresh world per call would scatter an operation across several buffers and apply none of
 * them together.
 *
 * Permissions are not consulted. On a build node there is no permission plugin at all - authority
 * comes from holding a writable build session, which the guard already established before this actor
 * was constructed. Reporting "yes" here therefore says "the build server already decided", not
 * "anyone may do anything".
 */
@Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
internal class MinestomPlayerActor(
    private val player: Player,
    private val world: GuardedWorld
) : AbstractPlayerActor() {

    private val target get() = world.target

    override fun getUniqueId(): UUID = player.uuid

    override fun getName(): String = player.username

    override fun getWorld(): World = world

    override fun getLocation(): Location {
        val pos = player.position
        return Location(world, Vector3.at(pos.x(), pos.y(), pos.z()), pos.yaw(), pos.pitch())
    }

    override fun setLocation(location: Location): Boolean {
        val position = location.toVector()
        player.teleport(Pos(position.x(), position.y(), position.z(), location.yaw, location.pitch))
        return true
    }

    override fun getItemInHand(handSide: HandSide): BaseItemStack {
        val hand = if (handSide == HandSide.MAIN_HAND) PlayerHand.MAIN else PlayerHand.OFF
        val stack = player.getItemInHand(hand)
        val type = ItemType.REGISTRY.get(stack.material().key().asString()) ?: return BaseItemStack(null)
        return BaseItemStack(type, stack.amount())
    }

    /**
     * Not wired: the only WorldEdit features that hand a player an item are the wand and navigation
     * wand commands, and this platform keeps the build server's own wand instead (see the command
     * bridge for which commands are exposed).
     */
    override fun giveItem(itemStack: BaseItemStack) = Unit

    /** `null` means "unlimited blocks", which is the only sensible answer in creative build mode -
     *  a block bag exists to charge survival players for what they place. */
    override fun getInventoryBlockBag(): BlockBag? = null

    override fun getState(): BaseEntity? = null

    /**
     * WorldEdit CUI is the client-side selection outline some clients draw from a plugin message.
     * Not implemented: the build server draws its own selection, and adding a second outline that
     * only some clients see would put two boxes on screen.
     */
    override fun dispatchCUIEvent(event: CUIEvent) = Unit

    override fun getLocale(): Locale = Locale.ENGLISH

    override fun printRaw(message: String) = sendPlain(message, null)

    override fun printDebug(message: String) = sendPlain(message, TextColor.GRAY)

    /**
     * Grey, not WorldEdit's usual light purple. A WorldEdit message on a build node is a build-server
     * message as far as the builder is concerned, and it reads as one - same prefix, same colour as
     * everything else the server says - rather than announcing which component produced it.
     */
    override fun print(message: String) = sendPlain(message, TextColor.GRAY)

    override fun printInfo(component: Component) = print(component.color(TextColor.GRAY))

    override fun printError(message: String) = sendPlain(message, TextColor.RED)

    override fun print(component: Component) {
        val adventure = ComponentBridge.toAdventure(component, locale)
        // WorldEdit prints an empty line for a few of its own no-op cases; sending one would put a
        // bare prefix on its own line in chat.
        if (PlainTextComponentSerializer.plainText().serialize(adventure).isEmpty()) return
        target.sendPrefixed(adventure)
    }

    private fun sendPlain(message: String, color: TextColor?) {
        val text = TextComponent.of(message)
        print(if (color == null) text else text.color(color))
    }

    override fun getSessionKey(): SessionKey = MinestomSessionKey(player.uuid, player.username)

    override fun getGroups(): Array<String> = emptyArray()

    /** See the class KDoc: authority is the build session, checked before this actor exists. */
    override fun hasPermission(permission: String): Boolean = true

    override fun remove(): Boolean = false

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getFacet(cls: Class<out T>): T? = null

    override fun equals(other: Any?): Boolean = other is MinestomPlayerActor && other.player.uuid == player.uuid

    override fun hashCode(): Int = player.uuid.hashCode()
}

/**
 * Identifies the [com.sk89q.worldedit.LocalSession] that holds a player's WorldEdit state (brush,
 * masks, tool bindings) between commands.
 *
 * Not persistent: a build visit is the natural lifetime for a brush binding, and persisting sessions
 * would mean writing WorldEdit session files next to a server whose own session state already lives
 * in Redis.
 */
internal class MinestomSessionKey(private val uuid: UUID, private val name: String) : SessionKey {
    override fun getUniqueId(): UUID = uuid
    override fun getName(): String = name
    override fun isActive(): Boolean =
        net.minestom.server.MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid) != null
    override fun isPersistent(): Boolean = false
}
