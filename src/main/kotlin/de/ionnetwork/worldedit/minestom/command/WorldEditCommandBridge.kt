package de.ionnetwork.worldedit.minestom.command

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.event.platform.CommandEvent
import com.sk89q.worldedit.event.platform.CommandSuggestionEvent
import de.ionnetwork.minestomconversion.extension.edit.EditGuards
import de.ionnetwork.minestomconversion.extension.edit.EditTarget
import de.ionnetwork.worldedit.minestom.actor.MinestomPlayerActor
import de.ionnetwork.worldedit.minestom.world.GuardedWorld
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.suggestion.Suggestion
import net.minestom.server.command.builder.suggestion.SuggestionEntry
import net.minestom.server.entity.Player
import net.minestom.server.instance.block.Block
import org.enginehub.piston.CommandManager
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Puts WorldEdit's commands on this server and routes them into a build session.
 *
 * Every command becomes a Minestom command named `/<worldedit name>`, so a builder types the classic
 * double slash (`//set`, `//hcyl`, `//copy`): the client strips one slash before the server sees it,
 * which is exactly how WorldEdit has always got its second one. The bare form (`/brush`) is also
 * registered, but only when nothing on the server already answers to that name - the build server's
 * own commands win a collision, since they are the ones with build-server semantics behind them.
 *
 * Dispatch never touches the world directly. It resolves the player's editable map through the guard,
 * hops onto that instance's thread, and hands WorldEdit a world that buffers writes behind the guard
 * (see [GuardedWorld]). A player with no editable session is refused here, in the build server's own
 * words, before WorldEdit is involved at all.
 */
internal object WorldEditCommandBridge {

    private val log = LoggerFactory.getLogger(WorldEditCommandBridge::class.java)

    /**
     * Edits run here rather than on the map thread, which is what stops a large `//set` freezing the
     * server. A small pool, not one thread per command: the expensive part of an edit is CPU, and
     * letting a handful of builders each saturate a core is the point at which a build node starts
     * affecting everything else on it.
     */
    private val workers = Executors.newFixedThreadPool(
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    ) { runnable -> Thread(runnable, "worldedit-edit").apply { isDaemon = true } }

    /**
     * One edit at a time per builder.
     *
     * Two of their own commands running at once would interleave reads and writes on the same map
     * with no ordering between them - the second would read a world the first has not finished
     * writing - and the `//undo` history would end up in whichever order the threads happened to
     * finish. Refusing the second is the honest behaviour.
     */
    private val running = ConcurrentHashMap.newKeySet<UUID>()

    /**
     * Commands WorldEdit offers that this server deliberately does not.
     *
     * Four reasons, and each name is here for exactly one of them:
     * - **The build server already owns it.** `schematic` (`/schem` is the DB-backed library, not
     *   files on a node's disk), `undo`/`redo`/`clearhistory` (one history, fed by every write path
     *   including map migrations, not just WorldEdit's), `wand`/`toggleeditwand` (the build server's
     *   wand is the one that draws a selection and respects a jump tile's box).
     * - **It bypasses the guard.** `superpickaxe`/`sp`/`pickaxe` destroy blocks through WorldEdit's
     *   tool path rather than an `EditSession`, so no extent - and therefore no build-server rule -
     *   sits in front of them.
     * - **It has nothing behind it here.** `snapshot`/`snap`/`restore` (no world backups),
     *   `chunkinfo`/`listchunks`/`delchunks` (Polar blobs, not anvil region files).
     * - **It is arbitrary code from chat.** `cs`/`.s` run CraftScripts.
     *
     * `sel`/`select`/`desel`/`deselect` are in the first group: they belong to the selection, which
     * the build server owns. WorldEdit's `//sel` also switches selector *shape* (polygonal, convex,
     * cylindrical), which the build server's box cannot represent - so the one selection a builder
     * has stays a cuboid rather than becoming something the wand can no longer draw.
     *
     * `world` is excluded separately: it lets a session pin an editing world by name, and on a build
     * node the world a player may edit is the guard's answer, not the player's.
     */
    private val EXCLUDED = setOf(
        "schematic", "schem",
        "undo", "redo", "clearhistory",
        "wand", "toggleeditwand",
        "sel", "select", "desel", "deselect",
        "superpickaxe", "pickaxe", "sp",
        "snapshot", "snap", "restore",
        "chunkinfo", "listchunks", "delchunks",
        "cs", ".s",
        "world"
    )

    /**
     * Registers every WorldEdit command this server should expose.
     *
     * The whole body is wrapped because of where it is called from: WorldEdit invokes this while
     * handling `PlatformsRegisteredEvent`, and its event bus catches and logs whatever a handler
     * throws - but the flag that marks WorldEdit initialized is only set *after* the handler
     * returns. So an exception here does not just lose the commands; it leaves WorldEdit permanently
     * half-initialized, and every later registry lookup fails with "WorldEdit is not initialized
     * yet" from somewhere completely unrelated. Losing command registration is recoverable and
     * visible; losing initialization is neither.
     */
    fun register(pistonManager: CommandManager) {
        try {
            registerAll(pistonManager)
        } catch (t: Throwable) {
            log.error("WorldEdit: command registration failed; WorldEdit is still usable via the API", t)
        }
    }

    private fun registerAll(pistonManager: CommandManager) {
        var registered = 0
        val taken = mutableListOf<String>()
        pistonManager.allCommands.forEach { command ->
            if (isExcluded(command.name)) return@forEach
            val names = buildNames(command.name, command.aliases)
            if (names.isEmpty()) {
                taken += command.name
                return@forEach
            }
            MinecraftServer.getCommandManager().register(build(names))
            registered++
        }
        log.info("WorldEdit: registered $registered commands")
        if (taken.isNotEmpty()) {
            // Not a debug detail: a name taken by something else means that WorldEdit command does
            // not exist on this server, and the only symptom in game is "Unknown command".
            log.warn("WorldEdit: {} command(s) unavailable, every name already taken: {}", taken.size, taken)
        }
    }

    /** Stops accepting edits and lets any in flight finish; called when the server shuts down. */
    fun shutdown() {
        workers.shutdown()
    }

    private fun isExcluded(name: String) = name.removePrefix("/") in EXCLUDED

    /**
     * The Minestom command names for one WorldEdit command.
     *
     * WorldEdit's own names are used verbatim, leading slash and all, and that is the whole trick:
     * a client strips one slash before the server sees the line, so a command WorldEdit calls `/set`
     * is typed `//set` and one it calls `brush` is typed `/brush` - exactly the spellings builders
     * already know. Normalizing the slashes instead would merge WorldEdit's own two distinct
     * commands `//size` (how big is my selection) and `/size` (how big is my brush) into one, and
     * one of them would silently lose.
     *
     * Every resulting name is then checked against what is already registered, and a taken one is
     * dropped. Minestom refuses a duplicate by throwing, and this loop registers over a hundred
     * commands: without the check, one collision - a build-server command added later that happens
     * to share a name - aborts the loop and takes every command after it down with it. That is
     * exactly what `/sel` did on the first run. Dropping the name costs one command and is reported;
     * throwing costs all of them and is not.
     */
    private fun buildNames(name: String, aliases: Collection<String>): List<String> =
        (listOf(name) + aliases)
            .filter { it.isNotEmpty() && !isExcluded(it) }
            .distinct()
            .filter { MinecraftServer.getCommandManager().getCommand(it) == null }

    private fun build(names: List<String>): Command {
        val command = Command(names.first(), *names.drop(1).toTypedArray())
        val arguments = ArgumentType.StringArray("args")
        arguments.setSuggestionCallback { sender, _, suggestion ->
            // Completion is a convenience; a failure in it must never be what stops a builder using
            // the command. It also fires once per keystroke, so an unguarded throw here floods the
            // log rather than reporting a problem once.
            runCatching { (sender as? Player)?.let { suggest(it, suggestion) } }
                .onFailure { log.warn("WorldEdit: completion failed for '{}'", suggestion.input, it) }
        }
        command.setDefaultExecutor { sender, context ->
            (sender as? Player)?.let { dispatch(it, context.commandName, emptyArray()) }
        }
        command.addSyntax({ sender, context ->
            (sender as? Player)?.let { dispatch(it, context.commandName, context.get(arguments)) }
        }, arguments)
        return command
    }

    /**
     * Answers tab completion for one WorldEdit command.
     *
     * Everything a builder types after the command name is one greedy argument as far as Minestom is
     * concerned, so Minestom itself has nothing to suggest - it does not know that `//set sto` wants
     * block names or that `//brush sph` wants brush names. WorldEdit does, through the same argument
     * converters that parse the command, so completion is asked of it rather than reimplemented here:
     * block and item names, patterns, masks, brush names, directions and expressions all come back
     * without this file knowing any of them exist.
     *
     * Completion is refused for a player with no editable session, exactly as the command itself
     * would be - there is nothing to complete against a map they may not edit.
     *
     * Runs on the caller's thread rather than the map thread, unlike [dispatch]: a suggestion has to
     * be answered while the player is still typing, and WorldEdit's suggesters read its registries,
     * never the world.
     */
    private fun suggest(player: Player, suggestion: Suggestion) {
        val guard = EditGuards.get() ?: return
        val target = guard.targetFor(player.uuid) ?: return

        val event = CommandSuggestionEvent(
            MinestomPlayerActor(player, GuardedWorld(target, "buildmap:${target.mapId()}")),
            worldEditSuggestionLine(suggestion.input)
        )
        WorldEdit.getInstance().eventBus.post(event)

        val suggestions = event.suggestions.filter { placeableOn(target, it.substring) }
        // Logged on every request: whether the client asks at all - and it only asks for the nodes it
        // managed to parse - is otherwise invisible from the server, and it is the first thing worth
        // knowing when completion "does nothing".
        log.debug(
            "WorldEdit: completion for '{}' by {} -> {} suggestion(s)",
            suggestion.input, player.username, suggestions.size
        )
        if (suggestions.isEmpty()) return

        // WorldEdit says which span of the line each suggestion replaces; Minestom carries one span
        // for the whole set, so the first is used for all. They always describe the token under the
        // cursor, which is the same token for every suggestion in one request.
        // WorldEdit's offsets are already in the client's coordinates: its argument string is
        // Minestom's input with one slash prepended, and the client's own text is the same - the
        // client strips exactly one slash before sending. So no conversion is needed, and adding one
        // would replace the wrong part of the line.
        val first = suggestions.first()
        suggestion.start = first.start
        suggestion.length = first.end - first.start
        suggestions.forEach { suggestion.addEntry(SuggestionEntry(it.substring)) }
    }

    /**
     * Whether a suggestion is worth offering on this map.
     *
     * WorldEdit knows every block the server has; it does not know that this particular map is
     * LEGACY_1_8 and will refuse anything without a 1.8 equivalent. Offering `copper_bulb` to a
     * builder working on a 1.8 map is worse than offering nothing - they pick it, press enter, and
     * get a refusal for a block the server itself suggested.
     *
     * Only entries that actually name a block are judged. A suggestion list is not just block names:
     * it carries pattern and mask syntax (`#clipboard`, `^`, `*`, `##wool`) and, for other commands,
     * brush names and directions. Anything that does not resolve to a block is left alone rather than
     * guessed at, so this can only ever remove a block the map would have rejected.
     */
    internal fun placeableOn(target: EditTarget, suggestion: String): Boolean {
        val block = runCatching { Block.fromKey(suggestion.substringBefore('[')) }.getOrNull() ?: return true
        return target.canEverPlace(block)
    }

    /**
     * Builds the command line to hand WorldEdit.
     *
     * **The slash is prepended, never replaced**, and this is the one detail the whole bridge turns
     * on. WorldEdit names its region commands with a leading slash of their own - the piston command
     * behind `//set` is literally called `/set`, with no alias - and it drops the first character of
     * whatever line it is given before matching. So the line has to be `//set stone`: one slash for
     * WorldEdit to discard, and the command's real name underneath.
     *
     * Normalizing the name to `set` first, which reads like the obvious thing to do, produces
     * `/set stone`, which WorldEdit reduces to the command `set` - a command that does not exist. It
     * then returns without a word: no error, no log, nothing happens at all. Commands whose piston
     * name has no slash (`brush`) work either way, which is what makes the bug look intermittent.
     */
    internal fun worldEditLine(commandName: String, args: Array<String>): String = buildString {
        append('/')
        append(commandName)
        args.forEach { append(' ').append(it) }
    }

    /**
     * Turns the line Minestom is completing into the line WorldEdit expects.
     *
     * Two adjustments, and the second is not obvious. The slash is the same one [worldEditLine]
     * explains: WorldEdit drops the first character before matching.
     *
     * The other is Minestom's. When the text ends with a space it appends a NUL character before
     * parsing - its own source calls this "probably the most hacky solution, but hey it works as
     * intended" - so that its parser has a token to chew on and can reach the next argument's
     * suggestion callback instead of deciding the line is finished. That NUL then arrives here.
     * WorldEdit has no such convention: it reads the NUL as the argument the player is typing and
     * dutifully reports that no block is called `\u0000`. The result is that completion works
     * everywhere except the one place a builder most wants it - right after `//set `, with nothing
     * typed yet - which is exactly how it was first reported.
     */
    internal fun worldEditSuggestionLine(minestomInput: String): String =
        "/" + minestomInput.removeSuffix("\u0000")

    /**
     * Runs one WorldEdit command for [player].
     *
     * The whole command executes inside a single instance tick. That is not laziness about async: the
     * build server's own edit commands already read half a million blocks in one tick, Minestom chunk
     * access is only safe from the instance thread, and running WorldEdit anywhere else would mean
     * either copying the world or racing it.
     */
    private fun dispatch(player: Player, commandName: String, args: Array<String>) {
        val guard = EditGuards.get()
        if (guard == null) {
            // Not a build node at all; no language manager to ask, so say nothing rather than
            // guessing at a translation key that this server may not have.
            log.debug("WorldEdit command from ${player.username} on a server with no edit guard")
            return
        }
        val target = guard.targetFor(player.uuid)
        if (target == null) {
            val reason = guard.noTargetReason(player.uuid)
            // Logged as well as sent: "my //set did nothing" is the most likely support question
            // here, and the player-facing answer is a chat line that has already scrolled away.
            log.debug("WorldEdit: refused {} for {} ({})", commandName, player.username, reason)
            guard.message(player.uuid, reason, emptyMap())
            return
        }

        val line = worldEditLine(commandName, args)

        if (!running.add(player.uuid)) {
            target.message("buildserver.edit.already_running")
            return
        }
        // Logged on the way in as well as out. An edit that never finishes, or never starts, is
        // otherwise indistinguishable from one the server never received - which is exactly the
        // question that came up first, and could not be answered from the log at all.
        log.info("WorldEdit: running '{}' for {}", line, player.username)
        workers.execute {
            val started = System.nanoTime()
            var world: GuardedWorld? = null
            try {
                world = runEdit(player, target, line)
            } catch (t: Throwable) {
                log.error("WorldEdit command '$line' from ${player.username} failed", t)
                target.message("buildserver.edit.failed")
            } finally {
                // Always cleared, including on failure: a progress bar left behind would sit in the
                // action bar for the rest of the session, claiming an edit is still running.
                target.reportProgress(null)
                running.remove(player.uuid)
                val millis = (System.nanoTime() - started) / 1_000_000
                // Chunk loading is broken out because it is the part most likely to dominate and the
                // least obvious: a wide edit can touch thousands of chunks whose contents it never
                // needs, and without this the time just looks like "WorldEdit is slow".
                log.info(
                    "WorldEdit: '{}' for {} finished in {}ms ({} chunks loaded in {}ms)",
                    line, player.username, millis, world?.chunkCount ?: 0, world?.chunkLoadMillis ?: 0
                )
            }
        }
    }

    /**
     * Runs one command with a world that reads through section snapshots rather than the live map.
     *
     * This is the whole of "async": the instance keeps ticking while WorldEdit iterates, because the
     * only things that touch the map are the section copies this asks for and the single batch the
     * edit ends in - both of which hop to the map thread themselves.
     */
    private fun runEdit(player: Player, target: EditTarget, line: String): GuardedWorld {
        val world = GuardedWorld(target, "buildmap:${target.mapId()}", offMapThread = true, commandLabel = line)
        val actor = MinestomPlayerActor(player, world)
        val session = WorldEdit.getInstance().sessionManager.get(actor)
        session.setBlockChangeLimit(target.maxBlocks())
        SessionBridge.seed(player.uuid, session, world, target)
        try {
            WorldEdit.getInstance().eventBus.post(CommandEvent(actor, line))
        } finally {
            SessionBridge.writeBack(player.uuid, session, world, target)
        }
        return world
    }
}
