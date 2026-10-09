package de.ionnetwork.worldedit.minestom

import de.ionnetwork.worldedit.minestom.actor.ComponentBridge
import net.kyori.adventure.text.format.NamedTextColor
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import com.sk89q.worldedit.util.formatting.text.TextComponent as WeText
import com.sk89q.worldedit.util.formatting.text.format.TextColor as WeColor

/**
 * WorldEdit's messages have to read as the build server's own: same grey, not WorldEdit's signature
 * light purple. A single pink line among the server's grey ones is the sort of thing that gets
 * noticed and never tracked down.
 */
class MessageStyleTest {

    @BeforeTest
    fun boot() {
        WorldEditTestPlatform.ensureStarted()
    }

    @Test
    fun `light purple becomes grey`() {
        val converted = ComponentBridge.toAdventure(
            WeText.of("42 blocks changed").color(WeColor.LIGHT_PURPLE),
            Locale.ENGLISH
        )
        assertEquals(NamedTextColor.GRAY, converted.color())
    }

    /** Errors stay red - the recolouring is about WorldEdit's house style, not about flattening
     *  everything the server says into one colour. */
    @Test
    fun `red stays red`() {
        val converted = ComponentBridge.toAdventure(
            WeText.of("Nope").color(WeColor.RED),
            Locale.ENGLISH
        )
        assertEquals(NamedTextColor.RED, converted.color())
    }

    /** WorldEdit colours plenty of components itself and hands them straight to print, so nested
     *  children have to be repainted too or the pink simply moves one level down. */
    @Test
    fun `nested light purple becomes grey`() {
        val converted = ComponentBridge.toAdventure(
            WeText.of("outer").color(WeColor.GRAY)
                .append(WeText.of("inner").color(WeColor.LIGHT_PURPLE)),
            Locale.ENGLISH
        )
        assertEquals(NamedTextColor.GRAY, converted.children().first().color())
    }
}
