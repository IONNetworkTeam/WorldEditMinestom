package de.ionnetwork.worldedit.minestom

import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import kotlin.test.Test

/**
 * When does Minestom actually reach a command argument's suggestion callback?
 *
 * The question that matters for a builder: typing `//set ` and pressing tab should offer block names
 * straight away, not only after a first character has been typed. No network or player is needed to
 * answer it - the command parser is reachable directly from a bare server init.
 */
class MinestomSuggestionParseTest {

    @Test
    fun `when is the suggestion callback reached`() {
        MinecraftServer.init()
        val sender = MinecraftServer.getCommandManager().consoleSender

        val fired = mutableListOf<String>()

        val plain = ArgumentType.StringArray("args")
        plain.setSuggestionCallback { _, _, s -> fired += "plain:'${s.input}'" }
        MinecraftServer.getCommandManager().register(
            Command("/set").apply {
                setDefaultExecutor { _, _ -> }
                addSyntax({ _, _ -> }, plain)
            }
        )

        val defaulted = ArgumentType.StringArray("args2")
        defaulted.setDefaultValue(arrayOf<String>())
        defaulted.setSuggestionCallback { _, _, s -> fired += "defaulted:'${s.input}'" }
        MinecraftServer.getCommandManager().register(
            Command("/hcyl").apply {
                setDefaultExecutor { _, _ -> }
                addSyntax({ _, _ -> }, defaulted)
            }
        )

        for (line in listOf("/set", "/set ", "/set s", "/hcyl", "/hcyl ", "/hcyl s")) {
            fired.clear()
            val suggestion = MinecraftServer.getCommandManager().parseCommand(sender, line).suggestion(sender)
            println("INPUT '$line' -> suggestion=${suggestion != null} fired=$fired")
        }
    }
}
