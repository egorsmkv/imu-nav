package org.imunav.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.imunav.core.util.LogRetention
import org.imunav.core.util.LogStorage
import org.imunav.core.util.TripFileLog
import java.util.concurrent.CompletableFuture

/** Device-local choices and the latest disk measurement shown in Settings. */
data class LogManagementStatus(val limitMb: Int = 0, val retentionDays: Int = 0, val storage: LogStorage? = null, val busy: Boolean = false, val failed: Boolean = false)

/** Settings submit work to the log writer, keeping cleanup and measurements off the main thread. */
class LogManagement(context: Context, private val files: TripFileLog, private val clearRecent: () -> Unit) {
    private val preferences = context.getSharedPreferences("log_management", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow(
        LogManagementStatus(
            limitMb = preferences.getInt("limit_mb", 0).takeIf { it in STORAGE_LIMITS_MB } ?: 0,
            retentionDays = preferences.getInt("retention_days", 0).takeIf { it in RETENTION_DAYS } ?: 0,
        ),
    )
    val status = _status.asStateFlow()

    init {
        applyPolicy()
    }

    fun setStorageLimit(megabytes: Int) {
        require(megabytes in STORAGE_LIMITS_MB)
        if (_status.value.busy) return
        preferences.edit { putInt("limit_mb", megabytes) }
        _status.value = _status.value.copy(limitMb = megabytes)
        applyPolicy()
    }

    fun setRetentionDays(days: Int) {
        require(days in RETENTION_DAYS)
        if (_status.value.busy) return
        preferences.edit { putInt("retention_days", days) }
        _status.value = _status.value.copy(retentionDays = days)
        applyPolicy()
    }

    fun refresh() {
        if (!_status.value.busy) observe(files.storage())
    }

    /** Called only after the UI confirms deletion of local diagnostic logs. */
    fun clear() {
        if (_status.value.busy) return
        clearRecent()
        observe(files.clear())
    }

    private fun applyPolicy() = observe(files.configure(LogRetention(_status.value.limitMb * BYTES_PER_MB, _status.value.retentionDays)))

    private fun observe(operation: CompletableFuture<LogStorage>) {
        _status.value = _status.value.copy(busy = true, failed = false)
        operation.whenComplete { storage, error ->
            main.post {
                _status.value = _status.value.copy(storage = storage ?: _status.value.storage, busy = false, failed = error != null)
            }
        }
    }

    companion object {
        val STORAGE_LIMITS_MB = listOf(0, 25, 100, 250)
        val RETENTION_DAYS = listOf(0, 7, 30, 90)
        private const val BYTES_PER_MB = 1024L * 1024
    }
}
