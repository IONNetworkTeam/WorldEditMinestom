package de.ionnetwork.worldedit.minestom.platform

import com.sk89q.worldedit.LocalConfiguration
import com.sk89q.worldedit.entity.Player
import com.sk89q.worldedit.extension.platform.AbstractPlatform
import com.sk89q.worldedit.extension.platform.Capability
import com.sk89q.worldedit.extension.platform.Preference
import com.sk89q.worldedit.util.SideEffect
import com.sk89q.worldedit.world.DataFixer
import com.sk89q.worldedit.world.World
import com.sk89q.worldedit.world.registry.Registries
import de.ionnetwork.worldedit.minestom.command.WorldEditCommandBridge
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.EntityType
import org.enginehub.piston.CommandManager

/**
 * WorldEdit's view of this server.
 *
 * Claims every [Capability] rather than only [Capability.WORLD_EDITING], because WorldEdit's
 * `queryCapability` throws when nothing provides one - and `EditSession`'s own constructor queries
 * `GAME_HOOKS` on every single edit. On a server with exactly one platform, "this one" is the only
 * possible answer to all of them.
 */
internal class MinestomPlatform(
    maxBlocks: Int,
    /** The command-registration step, injected so a test can boot the platform without a server to
     *  register commands with - see `WorldEditTestPlatform`. */
    private val commandRegistrar: (CommandManager) -> Unit = WorldEditCommandBridge::register
) : AbstractPlatform() {

    private val configuration = MinestomConfiguration(maxBlocks).apply { load() }
    private val registries = MinestomRegistries()

    override fun getRegistries(): Registries = registries

    /**
     * Used by WorldEdit only to stamp and read schematics and to drive its data fixer, neither of
     * which runs here - the build server owns schematics. Reported as 0, which WorldEdit reads as
     * "unknown", rather than a hardcoded number that would silently go stale as Minestom moves.
     */
    override fun getDataVersion(): Int = 0

    override fun getDataFixer(): DataFixer? = null

    override fun isValidMobType(type: String): Boolean = EntityType.fromKey(type) != null

    override fun reload() {
        configuration.load()
    }

    /**
     * There is no global world list on a build node: every session has its own instance, and which
     * one a player may edit is the guard's answer, not a lookup by name. WorldEdit uses this list for
     * `//world`-style commands, none of which are exposed.
     */
    override fun getWorlds(): List<World> = emptyList()

    override fun matchPlayer(player: Player): Player = player

    override fun matchWorld(world: World): World = world

    override fun registerCommands(commandManager: CommandManager) {
        commandRegistrar(commandManager)
    }

    /** Game hooks are WorldEdit reacting to vanilla block updates and player input. The build server
     *  routes both itself, so there is nothing to switch on or off. */
    override fun setGameHooksEnabled(enabled: Boolean) = Unit

    override fun getConfiguration(): LocalConfiguration = configuration

    override fun getVersion(): String = com.sk89q.worldedit.WorldEdit.getVersion()

    override fun getPlatformName(): String = "Minestom-Official"

    override fun getPlatformVersion(): String = MinecraftServer.VERSION_NAME

    override fun getCapabilities(): Map<Capability, Preference> =
        Capability.entries.associateWith { Preference.PREFERRED }

    /**
     * Side effects are vanilla's post-placement work: neighbour updates, lighting, redstone. A build
     * instance runs none of it, so reporting an empty set stops WorldEdit offering `//fast`-style
     * toggles for behaviour that does not exist here.
     */
    override fun getSupportedSideEffects(): Set<SideEffect> = emptySet()
}
