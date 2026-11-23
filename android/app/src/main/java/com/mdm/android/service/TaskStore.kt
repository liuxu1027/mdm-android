package com.mdm.android.service

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import uniffi.mdm_android.DownloadKind
import uniffi.mdm_android.DownloadState
import uniffi.mdm_android.DownloadTask
import java.util.concurrent.Executors

/**
 * 任务列表持久化：把任务记录存进应用私有目录的 SQLite 库，
 * 退出、重启、进程被杀后重进，列表还在（和 1DM 一样）。
 * 恢复的任务一律是“已暂停”，由用户点“继续”再下。
 */
class TaskStore(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    // 所有写操作串在一根单线程上，避免并发写乱序。
    private val writer = Executors.newSingleThreadExecutor()
    private val writeLock = Any()

    @Volatile
    private var lastSavedAtMs = 0L

    @Volatile
    private var lastSignature: String? = null

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "id TEXT PRIMARY KEY, " +
                "title TEXT NOT NULL, " +
                "source_url TEXT NOT NULL, " +
                "save_path TEXT NOT NULL, " +
                "kind TEXT NOT NULL, " +
                "state TEXT NOT NULL, " +
                "total_bytes INTEGER NOT NULL, " +
                "downloaded_bytes INTEGER NOT NULL, " +
                "error_message TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 只有 v1，暂无升级路径；以后加字段在这里处理。
    }

    /** 读出全部已保存的任务记录（启动恢复用）。 */
    fun loadAll(): List<DownloadTask> {
        val out = mutableListOf<DownloadTask>()
        val db = readableDatabase
        val c = db.query(TABLE, null, null, null, null, null, null)
        c.use {
            val iId = it.getColumnIndexOrThrow("id")
            val iTitle = it.getColumnIndexOrThrow("title")
            val iUrl = it.getColumnIndexOrThrow("source_url")
            val iPath = it.getColumnIndexOrThrow("save_path")
            val iKind = it.getColumnIndexOrThrow("kind")
            val iState = it.getColumnIndexOrThrow("state")
            val iTotal = it.getColumnIndexOrThrow("total_bytes")
            val iDone = it.getColumnIndexOrThrow("downloaded_bytes")
            val iErr = it.getColumnIndexOrThrow("error_message")
            while (it.moveToNext()) {
                val kind = kindFromString(it.getString(iKind)) ?: continue
                val state = stateFromString(it.getString(iState))
                out.add(
                    DownloadTask(
                        id = it.getString(iId),
                        title = it.getString(iTitle),
                        sourceUrl = it.getString(iUrl),
                        savePath = it.getString(iPath),
                        kind = kind,
                        state = state,
                        totalBytes = it.getLong(iTotal).toULong(),
                        downloadedBytes = it.getLong(iDone).toULong(),
                        speedBytesPerSec = 0u,
                        uploadSpeedBytesPerSec = 0u,
                        activeConnections = 0u,
                        errorMessage = it.getString(iErr)
                    )
                )
            }
        }
        return out
    }

    /**
     * 任务快照落盘：任务增减、状态变化时立刻写（同步，调用方是引擎回调线程）；
     * 只有进度数字变化时节流到 2 秒写一次（异步）。
     */
    fun saveThrottled(tasks: List<DownloadTask>) {
        val sig = tasks.sortedBy { it.id }
            .joinToString("|") { "${it.id}:${it.state.name}" }
        val now = SystemClock.elapsedRealtime()
        if (sig != lastSignature) {
            lastSignature = sig
            lastSavedAtMs = now
            saveAllInternal(tasks)
        } else if (now - lastSavedAtMs >= PROGRESS_SAVE_INTERVAL_MS) {
            lastSavedAtMs = now
            val snapshot = tasks.toList()
            writer.execute { saveAllInternal(snapshot) }
        }
    }

    /** 退出/销毁前立刻落盘（同步写，保证数据进了库才走）。 */
    fun saveNow(tasks: List<DownloadTask>) {
        lastSavedAtMs = SystemClock.elapsedRealtime()
        lastSignature = tasks.sortedBy { it.id }
            .joinToString("|") { "${it.id}:${it.state.name}" }
        saveAllInternal(tasks)
    }

    private fun saveAllInternal(tasks: List<DownloadTask>) {
        synchronized(writeLock) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (t in tasks) {
                    val v = ContentValues().apply {
                        put("id", t.id)
                        put("title", t.title)
                        put("source_url", t.sourceUrl)
                        put("save_path", t.savePath)
                        put("kind", t.kind.name)
                        put("state", t.state.name)
                        put("total_bytes", t.totalBytes.toLong())
                        put("downloaded_bytes", t.downloadedBytes.toLong())
                        put("error_message", t.errorMessage)
                    }
                    db.insertWithOnConflict(TABLE, null, v, SQLiteDatabase.CONFLICT_REPLACE)
                }
                if (tasks.isEmpty()) {
                    db.delete(TABLE, null, null)
                } else {
                    val placeholders = tasks.joinToString(",") { "?" }
                    db.delete(
                        TABLE,
                        "id NOT IN ($placeholders)",
                        tasks.map { it.id }.toTypedArray()
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun kindFromString(s: String?): DownloadKind? =
        DownloadKind.values().firstOrNull { it.name == s }

    private fun stateFromString(s: String?): DownloadState =
        DownloadState.values().firstOrNull { it.name == s } ?: DownloadState.PAUSED

    companion object {
        private const val DB_NAME = "mdm_tasks.db"
        private const val DB_VERSION = 1
        private const val TABLE = "tasks"
        private const val PROGRESS_SAVE_INTERVAL_MS = 2000L
    }
}
