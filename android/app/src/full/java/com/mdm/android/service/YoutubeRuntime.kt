package com.mdm.android.service

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

object YoutubeRuntime {
    const val ffmpegEnabled = true

    fun init(context: Context) {
        val appContext = context.applicationContext
        YoutubeDL.getInstance().init(appContext)
        FFmpeg.getInstance().init(appContext)
    }
}
