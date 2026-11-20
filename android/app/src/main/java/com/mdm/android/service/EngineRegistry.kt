package com.mdm.android.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.mdm_android.DownloadEngine
import uniffi.mdm_android.DownloadTask

object EngineRegistry {
    @Volatile
    var engine: DownloadEngine? = null
        private set

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    fun attach(instance: DownloadEngine) {
        engine = instance
        _tasks.value = instance.listTasks()
    }

    fun publish(snapshot: List<DownloadTask>) {
        _tasks.value = snapshot
    }
}
