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

    private var nativeTasks: List<DownloadTask> = emptyList()
    private var youtubeTasks: List<DownloadTask> = emptyList()

    @Synchronized
    fun attach(instance: DownloadEngine) {
        engine = instance
        nativeTasks = instance.listTasks()
        publishCombined()
    }

    @Synchronized
    fun publish(snapshot: List<DownloadTask>) {
        nativeTasks = snapshot
        publishCombined()
    }

    @Synchronized
    fun publishYoutube(snapshot: List<DownloadTask>) {
        youtubeTasks = snapshot
        publishCombined()
    }

    @Synchronized
    fun updateYoutube(id: String, transform: (DownloadTask) -> DownloadTask): Boolean {
        val index = youtubeTasks.indexOfFirst { it.id == id }
        if (index < 0) return false
        youtubeTasks = youtubeTasks.toMutableList().also { it[index] = transform(it[index]) }
        publishCombined()
        return true
    }

    @Synchronized
    fun addYoutube(task: DownloadTask) {
        youtubeTasks = youtubeTasks.filterNot { it.id == task.id } + task
        publishCombined()
    }

    @Synchronized
    fun removeYoutube(id: String): DownloadTask? {
        val removed = youtubeTasks.firstOrNull { it.id == id }
        youtubeTasks = youtubeTasks.filterNot { it.id == id }
        publishCombined()
        return removed
    }

    @Synchronized
    fun replaceYoutube(snapshot: List<DownloadTask>) {
        youtubeTasks = snapshot
        publishCombined()
    }

    private fun publishCombined() {
        _tasks.value = (nativeTasks + youtubeTasks).sortedByDescending { it.id }
    }
}
