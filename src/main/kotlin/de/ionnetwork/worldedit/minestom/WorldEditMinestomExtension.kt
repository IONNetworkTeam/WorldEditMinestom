package de.ionnetwork.worldedit.minestom

import com.sk89q.worldedit.LocalSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.event.platform.PlatformReadyEvent
import com.sk89q.worldedit.event.platform.PlatformsRegisteredEvent
import com.sk89q.worldedit.world.biome.BiomeType
import com.sk89q.worldedit.world.block.BlockType
import com.sk89q.worldedit.world.item.ItemType
import de.ionnetwork.minestomconversion.extension.Extension
import de.ionnetwork.minestomconversion.extension.edit.EditTarget
import de.ionnetwork.worldedit.minestom.command.SessionBridge
import de.ionnetwork.worldedit.minestom.command.WorldEditCommandBridge
import de.ionnetwork.worldedit.minestom.platform.MinestomPlatform
import de.ionnetwork.worldedit.minestom.world.GuardExtentListener
import net.minestom.server.MinecraftServer
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.item.Material
import org.slf4j.LoggerFactory

/**
 * WorldEdit, running on Minestom, inside the build server's rules.
 *
 * Lives in its own repository and ships as a separate jar because worldedit-core is GPLv3 and the
 * server it plugs into is not. The only thing crossing that line is the extension SPI
 * (`de.ionnetwork.minestomconversion.extension.edit`), which is the build server's own interface and
 * mentions nothing from WorldEdit - so the server links no GPL code and this jar links no closed
 * code. It is the same arrangement the v1 build server had with FAWE, which was also a separate
 * plugin for exactly this reason.
 */
class WorldEditMinestomExtension : Extension {

    private val log = LoggerFactory.getLogger(WorldEditMinestomExtension::class.java)
    private var platform: MinestomPlatform? = null

    override fun id(): String = "worldedit"

    override fun initialize(server: MinecraftServer?) {
        if (!editSpiIsCompatible()) return

        val platform = MinestomPlatform(MAX_BLOCKS)
        this.platform = platform

        // Order matters, and the two halves are not interchangeable:
        //  1. register the platform, so the registries below have somewhere to resolve materials and
        //     properties from - a BlockType built before this would cache an empty property map;
        //  2. populate the registries, so every block name this server knows exists as a WorldEdit
        //     BlockType before any command parser is asked to complete one;
        //  3. announce that the platforms are registered, which is what runs WorldEdit's capability
        //     selection and therefore what registers its commands with this platform - so it must
        //     come after the registries are full;
        //  4. announce that this platform is ready, which arms its executor and marks it live.
        //
        // Steps 3 and 4 are two different events and both are needed. PlatformReadyEvent alone looks
        // sufficient - there is even a handlePlatformReady method that does the rest - but that
        // method is deprecated and, crucially, not a subscriber, so posting only the ready event
        // leaves WorldEdit registered and command-less: no //set, no error, nothing in the log.
        // Before anything else: this is what puts the build server's rules above WorldEdit's own
        // counters and history, and a session created before it is registered would not be guarded.
        WorldEdit.getInstance().eventBus.register(GuardExtentListener)

        WorldEdit.getInstance().platformManager.register(platform)
        populateRegistries()
        WorldEdit.getInstance().eventBus.post(PlatformsRegisteredEvent())
        WorldEdit.getInstance().eventBus.post(PlatformReadyEvent(platform))

        // WorldEdit keeps every edit it has made per player so that its own //undo can walk back
        // through them. That command is not exposed here - the build server's /undo is the one
        // history, fed by every write path - so retaining them is a leak of a full copy of every edit
        // a builder has ever made this session.
        LocalSession.MAX_HISTORY_SIZE = 0

        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent::class.java) { event ->
            SessionBridge.forget(event.player.uuid)
        }

        log.info("WorldEdit ${WorldEdit.getVersion()} ready on Minestom")
    }

    override fun shutdown() {
        WorldEditCommandBridge.shutdown()
        platform?.let { WorldEdit.getInstance().platformManager.unregister(it) }
        platform = null
    }

    /**
     * Teaches WorldEdit every block, item and biome this server has.
     *
     * WorldEdit ships bundled data for all three, but it is frozen at whatever Minecraft version the
     * release was cut against and - for blocks - carries no properties at all. Registering from
     * Minestom's own registries instead means the two agree by construction: a block this server can
     * place is a block WorldEdit can name, and no more.
     */
    private fun populateRegistries() {
        // Membership is tested with keySet(), not get(): WorldEdit's block, item and biome registries
        // are constructed with its "check initialized" flag, so get() throws until the platform
        // manager has finished initializing - which only happens on the PlatformReadyEvent this
        // function must run *before*. register() and keySet() carry no such guard.
        val blockKeys = BlockType.REGISTRY.keySet()
        var blocks = 0
        for (block in Block.values()) {
            val id = block.key().asString()
            if (id !in blockKeys) {
                BlockType.REGISTRY.register(id, BlockType(id))
                blocks++
            }
        }
        val itemKeys = ItemType.REGISTRY.keySet()
        var items = 0
        for (material in Material.values()) {
            val id = material.key().asString()
            if (id !in itemKeys) {
                ItemType.REGISTRY.register(id, ItemType(id))
                items++
            }
        }
        val biomeKeys = BiomeType.REGISTRY.keySet()
        var biomes = 0
        for (key in MinecraftServer.getBiomeRegistry().keys()) {
            val id = key.key().asString()
            if (id !in biomeKeys) {
                BiomeType.REGISTRY.register(id, BiomeType(id))
                biomes++
            }
        }
        log.info("WorldEdit registries: $blocks blocks, $items items, $biomes biomes")
    }

    /**
     * Checks that the server's editing SPI has everything this build was compiled against.
     *
     * This ships as two jars that have to match: the extension is compiled against the SPI, but at
     * runtime resolves it from the server's own jar. Deploy one without the other and every single
     * command breaks - not with a clear message, but with a `NoSuchMethodError` thrown from deep
     * inside WorldEdit's command handling, once per command and once per keystroke of tab completion.
     * That is a genuinely baffling way to find out the jars are out of step, and it has already
     * happened once.
     *
     * Failing here instead turns it into one line naming exactly what is missing, and leaves the
     * server otherwise working.
     */
    private fun editSpiIsCompatible(): Boolean {
        val available = EditTarget::class.java.methods.mapTo(HashSet()) { it.name }
        val missing = REQUIRED_TARGET_METHODS.filterNot { it in available }
        if (missing.isEmpty()) return true
        log.error(
            "WorldEdit not loaded: this server's editing SPI is missing {} - the server jar is older " +
                "than this extension. Redeploy MinestomConversion and WorldEditMinestom together.",
            missing
        )
        return false
    }

    internal companion object {
        /**
         * Everything this extension calls on an [EditTarget]. Kept as names rather than signatures on
         * purpose: a rename or removal is what actually happens when the jars drift, and a name check
         * catches that without duplicating the whole interface here.
         *
         * A hand-written list can go stale in the other direction too - naming a method the SPI no
         * longer has, so a correctly matched pair reports itself as mismatched. `SpiCompatibilityTest`
         * exists to fail the build when that happens, because the alternative is finding out on a
         * running server that WorldEdit refuses to load for a reason that is not true.
         */
        val REQUIRED_TARGET_METHODS = listOf(
            "runOnMapThread", "ensureLoaded", "snapshotSection", "mapId", "minY", "maxY",
            "maxBlocks", "blockAt", "check", "canEverPlace", "beginEdit", "stage", "commitEdit",
            "abortEdit", "selection", "setSelection", "pos1", "pos2", "setPositions", "clipboard", "setClipboard", "sendPrefixed",
            "reportProgress", "message"
        )

        /**
         * Mirrors the build server's own per-operation cap so WorldEdit refuses an oversized command
         * in its own words before building the change set. The guard enforces the real limit either
         * way - this only decides whether the refusal arrives early or late.
         *
         * **The cap is about memory, not about Minestom being slow.** Measured on this pipeline, the
         * block iteration and gating cost roughly 0.5-0.8 microseconds per block - 250k blocks in
         * under 200ms - which Minestom makes cheap by having no lighting engine or neighbour updates
         * on the batch path. What actually binds is that [de.ionnetwork.worldedit.minestom.world.GuardedWorld]
         * buffers the whole operation before applying any of it, at roughly 400 bytes per block: that
         * is what makes a refused edit write nothing at all, and it is also what turns a large fill
         * into a few hundred MB of transient heap and the multi-second GC pauses that come with it.
         * Four million blocks exhausts a 2GB heap outright.
         *
         * Raising this trades build-node heap for edit size. Making it genuinely unbounded means
         * streaming the apply across ticks - WorldEdit's own `Operation` model is built for that -
         * and giving up the all-or-nothing guarantee, which is a product decision rather than a
         * tuning one.
         */
        const val MAX_BLOCKS = 5_000_000
    }
}
