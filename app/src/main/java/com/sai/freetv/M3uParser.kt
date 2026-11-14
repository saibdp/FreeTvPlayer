package com.sai.freetv

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
) {
    /** YouTube / Twitch pages are not media streams; they get handed to the matching app. */
    val isExternal: Boolean
        get() = EXTERNAL_HOSTS.any { url.contains(it, ignoreCase = true) }

    private companion object {
        val EXTERNAL_HOSTS = listOf("youtube.com", "youtu.be", "twitch.tv")
    }
}

/**
 * Minimal parser for extended M3U playlists:
 *
 *   #EXTINF:-1 tvg-name="..." tvg-logo="..." group-title="...",Display Name
 *   https://host/stream.m3u8
 */
object M3uParser {
    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parse(text: String): List<Channel> {
        val result = ArrayList<Channel>()
        var pendingInfo: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> pendingInfo = line
                line.startsWith("#") -> Unit
                else -> {
                    val info = pendingInfo
                    pendingInfo = null
                    // Skips placeholders such as "[NO PUBLIC STREAM]"
                    if (info != null && line.contains("://")) result.add(build(info, line))
                }
            }
        }
        return result
    }

    private fun build(info: String, url: String): Channel {
        val attrs = attrRegex.findAll(info)
            .associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
        val name = displayName(info)
            .ifBlank { attrs["tvg-name"].orEmpty() }
            .ifBlank { url }
        return Channel(
            name = name,
            url = url,
            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
            group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: "Other",
        )
    }

    /** The display name follows the first comma that is not inside a quoted attribute. */
    private fun displayName(info: String): String {
        var inQuotes = false
        for (i in info.indices) {
            when (info[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return info.substring(i + 1).trim()
            }
        }
        return ""
    }
}
