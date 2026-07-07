package com.gguf.zerocopy.domain.inference

import android.util.Log
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks long-running background tasks (model load, inference, download)
 * so they can be monitored and cancelled from a central UI.
 *
 * Singleton held by ZeroCopyApp.
 */
class JobManager {

    private val nextId = AtomicLong(0)
    private val jobs = ConcurrentHashMap<Long, TrackedJob>()

    data class TrackedJob(
        val id: Long,
        val label: String,
        val category: JobCategory,
        val coroutineJob: Job? = null,
        val onCancel: (() -> Unit)? = null,
        val startTimeMs: Long = System.currentTimeMillis()
    )

    enum class JobCategory { MODEL_LOAD, INFERENCE, DOWNLOAD, EXPORT, OTHER }

    /** Register a new task. Returns a job ID that can be used to cancel. */
    fun register(
        label: String,
        category: JobCategory,
        coroutineJob: Job? = null,
        onCancel: (() -> Unit)? = null
    ): Long {
        val id = nextId.incrementAndGet()
        jobs[id] = TrackedJob(id, label, category, coroutineJob, onCancel)
        Log.d("JobManager", "Registered job #$id: $label ($category)")
        return id
    }

    /** Mark a task as finished (removes it from the active list). */
    fun unregister(id: Long) {
        jobs.remove(id)
        Log.d("JobManager", "Unregistered job #$id")
    }

    /** Cancel a task. Calls the onCancel callback and cancels the coroutine job. */
    fun cancel(id: Long) {
        val job = jobs.remove(id) ?: return
        Log.d("JobManager", "Cancelling job #$id: ${job.label}")
        try { job.onCancel?.invoke() } catch (e: Exception) {
            Log.w("JobManager", "onCancel failed for job #$id: ${e.message}")
        }
        try { job.coroutineJob?.cancel() } catch (_: Exception) {}
    }

    /** Cancel all active tasks. */
    fun cancelAll() {
        val ids = jobs.keys.toList()
        ids.forEach { cancel(it) }
    }

    /** Snapshot of currently active jobs. */
    fun activeJobs(): List<TrackedJob> = jobs.values.toList().sortedBy { it.id }

    /** Number of active jobs. */
    val activeCount: Int get() = jobs.size
}
