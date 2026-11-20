package com.mdm.android.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.mdm.android.service.DownloadForegroundService
import com.mdm.android.service.EngineRegistry
import kotlinx.coroutines.flow.StateFlow
import uniffi.mdm_android.DownloadTask

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val tasks: StateFlow<List<DownloadTask>> = EngineRegistry.tasks

    fun ensureServiceStarted() {
        val ctx = getApplication<Application>()
        ContextCompat.startForegroundService(
            ctx,
            Intent(ctx, DownloadForegroundService::class.java)
        )
    }

    fun addLink(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return
        val ctx = getApplication<Application>()
        val action = if (trimmed.startsWith("magnet:?")) {
            DownloadForegroundService.ACTION_ADD_MAGNET
        } else {
            DownloadForegroundService.ACTION_ADD_HTTP
        }
        val intent = Intent(ctx, DownloadForegroundService::class.java).apply {
            this.action = action
            putExtra(DownloadForegroundService.EXTRA_URL, trimmed)
        }
        ContextCompat.startForegroundService(ctx, intent)
    }

    fun pause(id: String) {
        runCatching { EngineRegistry.engine?.pauseTask(id) }
    }

    fun resume(id: String) {
        runCatching { EngineRegistry.engine?.resumeTask(id) }
    }

    fun remove(id: String) {
        runCatching { EngineRegistry.engine?.removeTask(id) }
    }
}
