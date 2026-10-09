package de.ionnetwork.worldedit.minestom.platform

import com.sk89q.worldedit.LocalConfiguration
import java.nio.file.Path

/**
 * WorldEdit's configuration, stated in code rather than read from a `worldedit.yml`.
 *
 * A build node is provisioned, not hand-configured: its limits come from the build server's own
 * rules, which live in the guard on the other side of the extension boundary. A config file here
 * would be a second place to set the same limit, and the two would drift.
 *
 * [maxChangeLimit] is set from the guard's own per-operation cap so WorldEdit refuses an oversized
 * operation in its own words, early, instead of building half a million changes and having the guard
 * reject the finished set.
 */
internal class MinestomConfiguration(private val maxBlocks: Int) : LocalConfiguration() {

    override fun load() {
        defaultChangeLimit = maxBlocks
        maxChangeLimit = maxBlocks
        maxRadius = 500
        maxBrushRadius = 100
        maxSuperPickaxeSize = 8
        defaultVerticalHeight = 1
        defaultMaxPolygonalPoints = -1
        maxPolygonalPoints = -1
        defaultMaxPolyhedronPoints = -1
        maxPolyhedronPoints = -1
        // Build instances are creative; charging a player for blocks they place is a survival rule.
        useInventory = false
        useInventoryOverride = false
        useInventoryCreativeOverride = false
        // The super pickaxe destroys blocks through the tool path, not through an EditSession, so it
        // would sidestep the guard entirely. Left at its defaults but never exposed as a command.
        superPickaxeDrop = false
        superPickaxeManyDrop = false
        // WorldEdit's own logging and script engine have no place on a build node: the server logs
        // edits itself, and running CraftScripts would be arbitrary code execution from chat.
        logCommands = false
        scriptsDir = ""
        scriptTimeout = 0
        allowSymlinks = false
        // The client-side selection outline; the build server draws its own (see the actor's
        // dispatchCUIEvent).
        serverSideCUI = false
        showHelpInfo = true
        disallowedBlocks = emptySet()
    }

    /** WorldEdit writes session and schematic files relative to this. Nothing on this platform uses
     *  either - schematics are the build server's DB-backed library - but the directory still has to
     *  resolve, so it points at a subdirectory of the node's own working directory. */
    override fun getWorkingDirectoryPath(): Path = Path.of("worldedit")
}
