package com.mdm.android.service

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL

object YoutubeRuntime {
    const val youtubeEnabled = false
    const val ffmpegEnabled = false

    fun init(context: Context) {
        YoutubeDL.getInstance().init(context.applicationContext)
    }
}
