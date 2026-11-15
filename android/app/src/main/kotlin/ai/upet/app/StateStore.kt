package ai.upet.app

import android.content.Context

/** Serializes same-process store instances; failed writes require an explicit durable retry. */
internal class StateStore(context: Context, private val preferenceName: String = "upet_state") {
    data class ReadResult(val success: Boolean, val json: String?, val requiresCommit: Boolean = false)
    private val process = failures.computeIfAbsent(preferenceName) { ProcessFailure() }
    private val preferences by lazy {
        context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    }
    private var loaded = false
    private var stale = false
    private var observedGeneration = 0L
    private var committedJson: String? = null

    val notice: String get() = synchronized(process) {
        if (isStale()) STALE_NOTICE else process.failure.orEmpty()
    }
    val available: Boolean get() = synchronized(process) { process.failure == null && !isStale() }
    val needsReload: Boolean get() = synchronized(process) { isStale() || process.readFailure }
    val readFailed: Boolean get() = synchronized(process) { process.readFailure }

    private fun isStale() = stale || (loaded && (
        observedGeneration != process.generation ||
            (process.failure != null && !process.readFailure && process.pendingOwner !== this)))

    /** Call on resume before using the engine; retryRead supplies the replacement JSON. */
    fun checkForChanges(): Boolean = synchronized(process) {
        if (!loaded) stale = true
        else if (process.failure == null && !isStale()) matchesCurrent()
        isStale() || process.readFailure
    }

    /** Null means no reload or a read failure requiring explicit user recovery. */
    fun reloadForResume(): ReadResult? = synchronized(process) {
        if (process.readFailure) return@synchronized null
        val changed = checkForChanges()
        // The change check itself may have encountered an I/O failure.
        if (process.readFailure || !changed) return@synchronized null
        retryRead()
    }

    fun read(): String? = synchronized(process) {
        if (process.failure != null) {
            if (process.readFailure) return@synchronized null
            // A newly created Activity may resume the failed owner's pending state.
            // An already loaded peer must not adopt or overwrite that pending state.
            if (!loaded || process.pendingOwner === this) {
                process.pendingOwner = this
                loaded = true
                observedGeneration = process.generation
                stale = false
                return@synchronized process.pendingJson
            }
            stale = true
            return@synchronized null
        }
        try {
            preferences.getString(KEY, null).also {
                committedJson = it
                observedGeneration = process.generation
                loaded = true
                stale = false
            }
        } catch (_: Exception) {
            fail("저장 내용을 읽지 못했어요. 기존 기록을 덮지 않도록 앱 조작을 잠갔어요. 주차 상태에서 다시 읽을 수 있어요.", reading = true)
            null
        }
    }

    fun commit(json: String): Boolean = synchronized(process) {
        if (!loaded || isStale()) { stale = true; return@synchronized false }
        if (process.failure != null) {
            if (!process.readFailure && process.pendingOwner === this) process.pendingJson = json
            return@synchronized false
        }
        if (!matchesCurrent()) return@synchronized false
        if (json == committedJson) return@synchronized true
        write(json)
    }

    /** Never accept SharedPreferences memory after a failed commit as proof of disk persistence. */
    fun retryCommit(json: String): Boolean = synchronized(process) {
        if (!loaded || isStale() || process.readFailure) return@synchronized false
        if (process.failure != null) {
            if (process.pendingOwner !== this) { stale = true; return@synchronized false }
        } else if (!matchesCurrent()) return@synchronized false
        write(json)
    }

    /** Pending takeover is not a disk read or a successful commit; the failure lock stays set. */
    fun retryRead(): ReadResult = synchronized(process) {
        if (process.failure != null && !process.readFailure) {
            val pending = process.pendingJson ?: return@synchronized ReadResult(false, null)
            process.generation++
            process.pendingOwner = this
            observedGeneration = process.generation
            loaded = true
            stale = false
            return@synchronized ReadResult(true, pending, requiresCommit = true)
        }
        try {
            val json = preferences.getString(KEY, null)
            committedJson = json
            observedGeneration = process.generation
            loaded = true
            stale = false
            clearFailure()
            ReadResult(true, json)
        } catch (_: Exception) {
            fail("저장 내용을 다시 읽지 못했어요. 기존 기록은 덮어쓰지 않았어요.", reading = true)
            ReadResult(false, null)
        }
    }

    private fun matchesCurrent(): Boolean = try {
        if (preferences.getString(KEY, null) == committedJson) true
        else { stale = true; false }
    } catch (_: Exception) {
        fail("저장 내용을 읽지 못했어요. 기존 기록을 덮지 않도록 앱 조작을 잠갔어요. 주차 상태에서 다시 읽을 수 있어요.", reading = true)
        false
    }

    private fun write(json: String): Boolean {
        process.pendingJson = json
        process.pendingOwner = this
        return try {
            if (preferences.edit().putString(KEY, json).commit()) {
                committedJson = json
                process.generation++
                observedGeneration = process.generation
                clearFailure()
                true
            } else {
                fail("저장에 실패했어요. 이번 변경의 디스크 저장을 확인하지 못해 앱 조작을 잠갔어요.")
                false
            }
        } catch (_: Exception) {
            fail("저장에 실패했어요. 이번 변경의 디스크 저장을 확인하지 못해 앱 조작을 잠갔어요.")
            false
        }
    }

    private fun fail(message: String, reading: Boolean = false) {
        process.failure = message
        process.readFailure = reading
    }
    private fun clearFailure() {
        process.failure = null
        process.readFailure = false
        process.pendingJson = null
        process.pendingOwner = null
    }
    private class ProcessFailure {
        var failure: String? = null
        var readFailure = false
        var pendingJson: String? = null
        var pendingOwner: StateStore? = null
        var generation = 0L
    }
    companion object {
        private const val KEY = "state_json_v1"
        private const val STALE_NOTICE = "저장 내용이 바뀌었어요. 최신 기록을 다시 읽어 주세요."
        private val failures = java.util.concurrent.ConcurrentHashMap<String, ProcessFailure>()
    }
}
