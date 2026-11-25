package com.mdm.android.service

import android.content.Context
import uniffi.mdm_android.DownloadState
import uniffi.mdm_android.DownloadTask

/**
 * 保存任务累计展示所需的墙钟起止时间，避免列表项离开 Compose 后耗时丢失。
 * 暂停时冻结显示，恢复下载时继续计时；完成或失败后保留最终耗时。
 */
object DownloadTaskTiming {
    private const val PREFS_NAME = "download_task_timing"
    private const val STARTED_AT_PREFIX = "started_at:"
    private const val STOPPED_AT_PREFIX = "stopped_at:"
    private val lock = Any()

    fun startedAt(context: Context, taskId: String): Long =
        preferences(context).getLong(startedAtKey(taskId), 0L)

    fun stoppedAt(context: Context, taskId: String): Long =
        preferences(context).getLong(stoppedAtKey(taskId), 0L)

    /** 若 service 还未记录启动时间，则由 UI 首次观察到下载时补记。 */
    fun ensureStartedAt(context: Context, taskId: String, nowMillis: Long = System.currentTimeMillis()): Long =
        synchronized(lock) {
            val prefs = preferences(context)
            val key = startedAtKey(taskId)
            prefs.getLong(key, 0L).takeIf { it > 0L } ?: nowMillis.also {
                prefs.edit().putLong(key, it).apply()
            }
        }

    /** 由任务快照持久化流程调用；每个状态的时间点只在状态转换时写入。 */
    fun recordTaskStates(
        context: Context,
        tasks: List<DownloadTask>,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            val prefs = preferences(context)
            val editor = prefs.edit()
            var changed = false
            tasks.forEach { task ->
                val startKey = startedAtKey(task.id)
                val stopKey = stoppedAtKey(task.id)
                val startedAt = prefs.getLong(startKey, 0L)
                val stoppedAt = prefs.getLong(stopKey, 0L)
                when (task.state) {
                    DownloadState.DOWNLOADING -> {
                        if (startedAt <= 0L) {
                            editor.putLong(startKey, nowMillis)
                            changed = true
                        }
                        if (stoppedAt > 0L) {
                            editor.remove(stopKey)
                            changed = true
                        }
                    }
                    DownloadState.PAUSED,
                    DownloadState.COMPLETED,
                    DownloadState.FAILED -> {
                        if (startedAt > 0L && stoppedAt <= 0L) {
                            editor.putLong(stopKey, nowMillis)
                            changed = true
                        }
                    }
                    DownloadState.QUEUED -> Unit
                }
            }
            if (changed) editor.apply()
        }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun startedAtKey(taskId: String) = "$STARTED_AT_PREFIX$taskId"
    private fun stoppedAtKey(taskId: String) = "$STOPPED_AT_PREFIX$taskId"
}
