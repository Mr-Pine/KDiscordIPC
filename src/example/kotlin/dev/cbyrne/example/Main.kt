package dev.cbyrne.example

import dev.cbyrne.kdiscordipc.KDiscordIPC
import dev.cbyrne.kdiscordipc.core.event.DiscordEvent
import dev.cbyrne.kdiscordipc.core.event.impl.*
import dev.cbyrne.kdiscordipc.core.packet.outbound.impl.SetVoiceSettingsPacket
import dev.cbyrne.kdiscordipc.core.util.Platform
import dev.cbyrne.kdiscordipc.core.util.json
import dev.cbyrne.kdiscordipc.core.util.platform
import dev.cbyrne.kdiscordipc.data.activity.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.File
import java.util.*
import kotlin.time.Duration.Companion.seconds

val logger: Logger = LogManager.getLogger("Example")

suspend fun main() {
    val ipc = KDiscordIPC(clientID)//"945428344806183003")
    logger.info("Starting example!")

    ipc.on<ReadyEvent> {
        logger.info("Ready! (${data.user.username}#${data.user.discriminator})")

        // Set the user's activity (a.k.a. rich presence)
        ipc.activityManager.setActivity("Hello", "world") {
            largeImage("https://avatars.githubusercontent.com/u/71222289?v=4", "KDiscordIPC")
            smallImage("https://avatars.githubusercontent.com/u/71222289?v=4", "Testing")

            party(UUID.randomUUID().toString(), listOf(1, 2))
            secrets(UUID.randomUUID().toString())
//            button("Click me", "https://google.com")
            timestamps(System.currentTimeMillis(), System.currentTimeMillis() + 50000)
        }

        // Subscribe to some events
        ipc.subscribe(DiscordEvent.CurrentUserUpdate)
        ipc.subscribe(DiscordEvent.ActivityJoinRequest)
        ipc.subscribe(DiscordEvent.ActivityJoin)
        ipc.subscribe(DiscordEvent.ActivityInvite)
        ipc.subscribe(DiscordEvent.ActivitySpectate)

        // Get a specific user by ID
        val user = ipc.userManager.getUser("843135686173392946")
        logger.info("User by ID: $user")

        // Get the user's friend list
        val relationships = ipc.relationshipManager.getRelationships()
        logger.info("Relationships: ${relationships.size}")

        // Folder and File where the access and refresh token will be saved so that they don't have to confirm every time
        val dataFolder =
            File(if (platform == Platform.WINDOWS) "${System.getenv("APPDATA")}/KDiscordIPC" else "${System.getenv("HOME")}/.KDiscordIPC")
        val authFile = File("${dataFolder.absolutePath}/authentication.json")

        // http client for getting the access token
        val ktorClient = HttpClient()

        var accessToken: String? = null

        // If there is a previous token
        if (authFile.exists()) {

            try {
                val timelessResponse = json.decodeFromString<TimelessOAuthResponse>(authFile.readText())

                // Check if it's expired and if so get a new one with the refresh token
                if (timelessResponse.expiresOn < Clock.System.now()) {

                    val response = ktorClient.submitForm(
                        url = "https://discord.com/api/v8/oauth2/token",
                        formParameters = Parameters.build {
                            append("client_id", clientID)
                            append("client_secret", clientSecret)
                            append("grant_type", "refresh_token")
                            append("refresh_token", timelessResponse.refreshToken)
                        }
                    )

                    val oauthResponse = json.decodeFromString<OAuthResponse>(response.body())
                    logger.info(oauthResponse)

                    accessToken = oauthResponse.accessToken

                    saveOAuthResponse(oauthResponse, dataFolder, authFile)
                } else {
                    accessToken = timelessResponse.accessToken
                }
            } catch (exception: Exception) {
                logger.error("Refresh token invalid")
            }
        }

        // If token could not be fetched from saved credentials (missing authentication.json or invalid refresh token) get a new one by prompting the user
        if (accessToken == null) {

            // Request authorization from the user. See https://discord.com/developers/docs/topics/rpc#authorize
            val authorization =
                ipc.applicationManager.authorize(
                    scopes = arrayOf("identify", "rpc", "rpc.voice.read"),
                    "971413122470531142"
                )
            logger.info("Authorization: $authorization")

            val response = ktorClient.submitForm(
                url = "https://discord.com/api/v8/oauth2/token",
                formParameters = Parameters.build {
                    append("client_id", clientID)
                    append("client_secret", clientSecret)
                    append("code", authorization.code)
                    append("grant_type", "authorization_code")
                    append("redirect_uri", "http://127.0.0.1")
                }
            )

            logger.info(response.body<String>())
            val oauthResponse = json.decodeFromString<OAuthResponse>(response.body())
            logger.info(oauthResponse)

            accessToken = oauthResponse.accessToken

            saveOAuthResponse(oauthResponse, dataFolder, authFile)
        }

        ktorClient.close()

        // Authenticate with the client and get an oauth token for the currently logged-in user
        val oauthToken = ipc.applicationManager.authenticate(accessToken)
        logger.info("Received oauth token from Discord! Expires on: ${oauthToken.expires}")

        // Prints the current voice settings
        val voiceSettings = ipc.voiceSettingsManager.getVoiceSettings()
        logger.info("Voice Settings: $voiceSettings")

        // Mutes the user
        ipc.voiceSettingsManager.setVoiceSettings(SetVoiceSettingsPacket.VoiceSettingArguments(mute = true))

        ipc.voiceSettingsManager.subscribeToVoiceSettingsUpdate()
    }

    ipc.on<ErrorEvent> {
        logger.error("IPC communication error (${data.code}): ${data.message}")
    }

    ipc.on<CurrentUserUpdateEvent> {
        logger.info("Current user updated!")
    }

    ipc.on<ActivityJoinEvent> {
        logger.info("The user has joined someone else's party! ${data.secret}")
    }

    ipc.on<ActivityInviteEvent> {
        logger.info("We have been invited to join ${data.user.username}'s party! (${data.activity.party.id})")

        ipc.activityManager.acceptInvite(data)
    }

    ipc.on<VoiceSettingsUpdateEvent> {
        logger.info("Voice settings updated! User is now ${if (this.data.mute) "" else "not "}muted")
    }

    ipc.connect()
}

@Suppress("unused")
@Serializable
data class OAuthResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("refresh_token") val refreshToken: String,
    val scope: String
)

@Suppress("MemberVisibilityCanBePrivate", "unused")
@Serializable
class TimelessOAuthResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_on") val expiresOn: Instant,
    @SerialName("refresh_token") val refreshToken: String,
    val scope: String
) {
    constructor(response: OAuthResponse) : this(
        accessToken = response.accessToken,
        tokenType = response.tokenType,
        refreshToken = response.refreshToken,
        scope = response.scope,
        expiresOn = Clock.System.now() + response.expiresIn.seconds
    )
}

fun saveOAuthResponse(response: OAuthResponse, directory: File, file: File) {

    if (!directory.exists()) directory.mkdir()
    file.writeText(
        json.encodeToString(
            TimelessOAuthResponse.serializer(), TimelessOAuthResponse(response)
        )
    )
}