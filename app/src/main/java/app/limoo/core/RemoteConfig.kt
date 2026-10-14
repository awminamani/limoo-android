package app.limoo.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Service-wide switches for the Armio customer build, fetched from [app.limoo.Armio.CONFIG_URL]
 * at launch and cached on disk by the Store, so the last known state keeps applying offline:
 *
 *  - [enabled] = false closes the service (the gate screen replaces the whole UI);
 *  - [minVersion] above the installed versionCode forces an update through the same gate;
 *  - [message] is an announcement shown on Home ("" = none);
 *  - [apkUrl] is where the gate's Update button points ("" = no button).
 *
 * The file is a tiny static JSON on the Armio server - editing it IS the control panel.
 */
@Serializable
data class RemoteCfg(
    val enabled: Boolean = true,
    val minVersion: Int = 0,
    val latestVersion: Int = 0,
    val apkUrl: String = "",
    val message: String = "",
)

object RemoteConfig {
    private val json = Json { ignoreUnknownKeys = true }

    /** The fetched config, or null on any failure (caller keeps the cached copy). */
    fun fetch(url: String): RemoteCfg? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "Armio/1.0")
            connectTimeout = 8_000; readTimeout = 8_000
        }
        try {
            if (c.responseCode !in 200..299) null
            else json.decodeFromString(RemoteCfg.serializer(), c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}
