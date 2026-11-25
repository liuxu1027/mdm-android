package com.mdm.android.ui

import android.net.Uri

fun isYouTubePageLink(input: String): Boolean {
    val value = input.trim()
    if (value.isEmpty()) return false
    val withScheme = if (value.contains("://")) value else "https://$value"
    val host = runCatching { Uri.parse(withScheme).host?.lowercase() }.getOrNull() ?: return false
    return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
}

fun isYouTubePlaylistLink(input: String): Boolean {
    val value = input.trim()
    if (value.isEmpty()) return false
    val withScheme = if (value.contains("://")) value else "https://$value"
    val uri = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return false
    return uri.path.orEmpty().contains("/playlist", ignoreCase = true) ||
        !uri.getQueryParameter("list").isNullOrBlank()
}
