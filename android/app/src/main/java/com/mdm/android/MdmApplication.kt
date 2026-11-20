package com.mdm.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class MdmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_DOWNLOADS,
                "下载任务与 BT 传输",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示后台 HTTP 多线程与 BT/磁力下载进度"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "mdm_downloads"
    }
}
