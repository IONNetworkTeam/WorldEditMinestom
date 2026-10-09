package de.ionnetwork.worldedit.minestom

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.event.platform.PlatformReadyEvent
import com.sk89q.worldedit.event.platform.PlatformsRegisteredEvent
import com.sk89q.worldedit.world.block.BlockType
import com.sk89q.worldedit.world.item.ItemType
import de.ionnetwork.worldedit.minestom.platform.MinestomPlatform
import de.ionnetwork.worldedit.minestom.world.GuardExtentListener
import net.minestom.server.instance.block.Block
import net.minestom.server.item.Material

/**
 * Boots WorldEdit against the Minestom platform once per JVM, the same way the extension does.
 *
 * Booting is not a test fixture detail here - it *is* one of the things under test. WorldEdit's
 * registries refuse to answer before its platform manager is initialized, its block types resolve
 * their properties through the registered platform, and its commands only register on the second of
 * two events. Every one of those tripped this platform up during development, so a test that reaches
 * a `BlockState` at all has already proved the sequence works.
 *
 * Biomes are the one thing skipped: Minestom's biome registry is per-server and there is no server
 * here. Nothing under test reads a biome.
 */
internal object WorldEditTestPlatform {

    private var started = false

    @Synchronized
    fun ensureStarted() {
        if (started) return
        started = true
        // No command registrar: there is no Minestom server here to register commands with, and
        // WorldEdit's own capability initialization would abort on the resulting exception - leaving
        // it permanently uninitialized, which is what the production code now guards against too.
        WorldEdit.getInstance().eventBus.register(GuardExtentListener)
        val platform = MinestomPlatform(500_000) { }
        WorldEdit.getInstance().platformManager.register(platform)
        val blockKeys = BlockType.REGISTRY.keySet()
        for (block in Block.values()) {
            val id = block.key().asString()
            if (id !in blockKeys) BlockType.REGISTRY.register(id, BlockType(id))
        }
        val itemKeys = ItemType.REGISTRY.keySet()
        for (material in Material.values()) {
            val id = material.key().asString()
            if (id !in itemKeys) ItemType.REGISTRY.register(id, ItemType(id))
        }
        WorldEdit.getInstance().eventBus.post(PlatformsRegisteredEvent())
        WorldEdit.getInstance().eventBus.post(PlatformReadyEvent(platform))
    }
}
