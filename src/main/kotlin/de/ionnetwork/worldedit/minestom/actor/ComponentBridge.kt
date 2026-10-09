package de.ionnetwork.worldedit.minestom.actor

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.util.formatting.WorldEditText
import com.sk89q.worldedit.util.formatting.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer as AdventureGson
import java.util.Locale
import com.sk89q.worldedit.util.formatting.text.Component as WeComponent

/**
 * Turns WorldEdit's text into Adventure text.
 *
 * WorldEdit bundles its own fork of an old Adventure, so its `Component` and the one Minestom sends
 * are unrelated types that merely look alike. JSON is the one representation both agree on, which is
 * also why this is not simply flattened to a plain string: WorldEdit leans heavily on colour to make
 * its output readable (green for counts, red for errors) and on translatable components for block
 * names, and flattening would throw both away.
 *
 * [WorldEditText.format] runs first so WorldEdit resolves its own `worldedit.*` strings against the
 * player's locale before the handover - after conversion it no longer can, because its translation
 * manager only understands its own component type.
 */
internal object ComponentBridge {

    fun toAdventure(component: WeComponent, locale: Locale): Component {
        val localized = WorldEdit.getInstance().translationManager.convertText(
            WorldEditText.format(component, locale),
            locale
        )
        return recolor(AdventureGson.gson().deserialize(GsonComponentSerializer.INSTANCE.serialize(localized)))
    }

    /**
     * Repaints WorldEdit's signature light purple as grey, everywhere it appears.
     *
     * The actor already reports informational messages in grey, which covers the messages that go
     * through [com.sk89q.worldedit.extension.platform.Actor.printInfo]. This is the backstop for the
     * ones that do not: plenty of WorldEdit's own call sites colour a component themselves and hand
     * it straight to `print`, and a single pink line among the build server's grey ones is exactly
     * the sort of thing that is noticed and never tracked down.
     *
     * Only that one colour is touched. Red errors, and anything WorldEdit deliberately colours to
     * mean something (a block name, a clickable suggestion), keep their own styling.
     */
    private fun recolor(component: Component): Component {
        val repainted = if (component.color() == NamedTextColor.LIGHT_PURPLE) {
            component.color(NamedTextColor.GRAY)
        } else {
            component
        }
        val children = repainted.children()
        if (children.isEmpty()) return repainted
        return repainted.children(children.map { recolor(it) })
    }
}
