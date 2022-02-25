package dev.cbyrne.example

import dev.cbyrne.kdiscordipc.KDiscordIPC
import dev.cbyrne.kdiscordipc.activity.*
import dev.cbyrne.kdiscordipc.event.impl.ReadyEvent
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

val logger: Logger = LogManager.getLogger("Example")

suspend fun main() {
    val ipc = KDiscordIPC("945428344806183003")
    logger.info("Starting example!")

    ipc.activityManager.activity = activity("Hello", "world") {
        largeImage("https://avatars.githubusercontent.com/u/71222289?v=4", "KDiscordIPC")
        smallImage("https://avatars.githubusercontent.com/u/71222289?v=4", "Testing")

        party("test", listOf(1, 2))
        button("Click me", "https://google.com")
        timestamps(System.currentTimeMillis(), System.currentTimeMillis() + 50000)
    }

    ipc.on<ReadyEvent> {
        logger.info("Ready! (${data.user.username}#${data.user.discriminator})")

        val user = ipc.userManager.getUser("843135686173392946")
        logger.info(user)
    }

    ipc.connect()
}