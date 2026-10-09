package de.ionnetwork.worldedit.minestom

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.event.platform.CommandSuggestionEvent
import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.internal.cui.CUIEvent
import com.sk89q.worldedit.session.SessionKey
import com.sk89q.worldedit.util.formatting.text.Component
import de.ionnetwork.worldedit.minestom.command.WorldEditCommandBridge
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tab completion, which for a WorldEdit command is not a nicety: every argument is a block name, a
 * pattern, a mask or a brush, and `//set ` with no completion means typing `polished_blackstone_brick_wall`
 * by hand.
 *
 * Minestom cannot help - as far as it is concerned everything after the command name is one greedy
 * string - so the suggestions have to come from WorldEdit's own argument converters. These check that
 * they do, and that the offsets handed back line up with what the client will replace.
 */
class SuggestionTest {

    @BeforeTest
    fun boot() {
        WorldEditTestPlatform.ensureStarted()
    }

    /**
     * [minestomInput] is what Minestom parses, i.e. what the client typed minus one slash. The bridge
     * adds that slash back before handing it to WorldEdit, so these go through the same construction
     * the live path uses rather than a hand-written approximation of it.
     */
    private fun suggest(minestomInput: String): List<String> {
        val event = CommandSuggestionEvent(
            TestActor(),
            WorldEditCommandBridge.worldEditSuggestionLine(minestomInput)
        )
        WorldEdit.getInstance().eventBus.post(event)
        return event.suggestions.map { it.substring }
    }

    @Test
    fun `a partial block name suggests blocks`() {
        val suggestions = suggest("/set ston")
        assertTrue(
            suggestions.any { it.endsWith("stone") },
            "no block suggestions for '//set ston', got $suggestions"
        )
    }

    /**
     * The regression that made completion useless exactly where it is wanted most: right after
     * `//set `, before anything has been typed.
     *
     * Minestom appends a NUL character to a line ending in a space so its own parser has a token to
     * reach the next argument with. WorldEdit has no such convention and reads that NUL as the word
     * being completed, so it correctly reports that no block is named `\u0000` - zero suggestions,
     * no error, and completion that only ever works once a first character has been typed.
     */
    @Test
    fun `an empty argument suggests something to start from`() {
        assertTrue(
            suggest("/set \u0000").isNotEmpty(),
            "no suggestions for '//set ' - Minestom's NUL placeholder is reaching WorldEdit"
        )
    }

    @Test
    fun `the NUL placeholder never reaches WorldEdit`() {
        assertEquals("//set ", WorldEditCommandBridge.worldEditSuggestionLine("/set \u0000"))
        assertEquals("//set s", WorldEditCommandBridge.worldEditSuggestionLine("/set s"))
        assertEquals("/brush ", WorldEditCommandBridge.worldEditSuggestionLine("brush \u0000"))
    }

    /** The span still has to land at the cursor when the token is empty, or the client inserts the
     *  completion in the wrong place. */
    @Test
    fun `an empty token is replaced at the end of the line`() {
        val line = WorldEditCommandBridge.worldEditSuggestionLine("/set \u0000")
        val event = CommandSuggestionEvent(TestActor(), line)
        WorldEdit.getInstance().eventBus.post(event)
        val first = event.suggestions.firstOrNull() ?: error("no suggestions")
        assertEquals(line.length, first.start)
        assertEquals(line.length, first.end)
    }

    @Test
    fun `a subcommand name is suggested`() {
        val suggestions = suggest("/brush sph")
        assertTrue(
            suggestions.any { it.contains("sphere") },
            "no brush suggestions for '//brush sph', got $suggestions"
        )
    }

    /**
     * The regression that made every slash-named command a silent no-op.
     *
     * WorldEdit's region commands carry a leading slash in their own name - the piston command behind
     * `//set` is called `/set`, with no alias - and WorldEdit drops the first character of the line
     * before matching. Normalizing the name to `set` first reads like the obvious thing to do and
     * produces a line WorldEdit reduces to a command that does not exist, whereupon it returns
     * without a word. Commands whose name has no slash (`brush`) work either way, which is exactly
     * what made this look like it was working.
     */
    @Test
    fun `a slash-named command keeps its own slash`() {
        assertEquals("//set stone", WorldEditCommandBridge.worldEditLine("/set", arrayOf("stone")))
    }

    @Test
    fun `a bare-named command gets exactly one slash`() {
        assertEquals("/brush sphere stone", WorldEditCommandBridge.worldEditLine("brush", arrayOf("sphere", "stone")))
    }

    @Test
    fun `a command with no arguments still builds a line`() {
        assertEquals("//up", WorldEditCommandBridge.worldEditLine("/up", emptyArray()))
    }

    /**
     * The span WorldEdit reports has to line up with what the client will replace, or a completed
     * `//set ston` comes out as `//sestone`. It needs no conversion: WorldEdit's argument string and
     * the client's text are the same string, because both carry exactly one slash more than what
     * Minestom parses.
     */
    @Test
    fun `the reported span covers the token being completed`() {
        val clientText = "//set ston"
        val event = CommandSuggestionEvent(TestActor(), clientText)
        WorldEdit.getInstance().eventBus.post(event)
        val first = event.suggestions.firstOrNull() ?: error("no suggestions to check offsets with")
        assertEquals("ston", clientText.substring(first.start, first.end))
    }
}

/**
 * The smallest thing WorldEdit will accept as a command sender. Completion needs a session owner (to
 * look up a `LocalSession`) and nothing else - the suggesters read WorldEdit's registries, not the
 * world - which is what makes this testable without a server at all.
 */
private class TestActor : Actor {
    private val uuid: UUID = UUID.randomUUID()

    override fun getName(): String = "TestActor"
    override fun getUniqueId(): UUID = uuid
    override fun printRaw(msg: String) = Unit
    override fun printDebug(msg: String) = Unit
    override fun print(msg: String) = Unit
    override fun printError(msg: String) = Unit
    override fun print(component: Component) = Unit
    override fun canDestroyBedrock(): Boolean = true
    override fun isPlayer(): Boolean = true
    override fun openFileOpenDialog(extensions: Array<out String>?): File? = null
    override fun openFileSaveDialog(extensions: Array<out String>?): File? = null
    override fun dispatchCUIEvent(event: CUIEvent) = Unit
    override fun getLocale(): Locale = Locale.ENGLISH
    override fun getSessionKey(): SessionKey = object : SessionKey {
        override fun getUniqueId(): UUID = uuid
        override fun getName(): String = "TestActor"
        override fun isActive(): Boolean = true
        override fun isPersistent(): Boolean = false
    }
    override fun getGroups(): Array<String> = emptyArray()
    override fun checkPermission(permission: String) = Unit
    override fun hasPermission(permission: String): Boolean = true
}
