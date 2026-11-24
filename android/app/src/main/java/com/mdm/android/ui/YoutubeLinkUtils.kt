package com.mdm.android.ui

import android.net.Uri

fun isYouTubePageLink(input: String): Boolean {
    val value = input.trim()
    if (value.isEmpty()) return false
    val withScheme = if (value.contains("://")) value else "https://$value"
    val host = runCatching { Uri.parse(withScheme).host?.lowercase() }.getOrNull() ?: return false
    return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
}
