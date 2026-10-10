package com.chloeyeo.friendorfoe.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Fire-and-forget delivery to the laptop dashboard (`laptop/dashboard/app.py`, JDG-03). Every call returns at
 * once: events go into a bounded queue (64, the oldest is dropped when full), frames and thumbnails into slots
 * that keep only the latest, and one background worker per queue sends them with a 500 ms call timeout. Failures
 * are counted, never thrown and never retried: the dashboard is a side-car, not the demo.
 * `android:usesCleartextTraffic="true"` in the manifest is what allows the plain `http://` LAN target.
 *
 * @param baseUrl e.g. `http://192.168.1.2:8081` (dashboard on 8081: 8080 is taken on the integration lead's laptop)
 */
class EventClient(private val baseUrl: String) {
    private class Post(val path: String, val body: okhttp3.RequestBody)

    private val http = OkHttpClient.Builder().callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<Post>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val frames = Channel<Post>(Channel.CONFLATED)
    private val thumbnails = Channel<Post>(Channel.CONFLATED)

    val sent = AtomicLong()
    val failed = AtomicLong()

    init {
        for (channel in listOf(events, frames, thumbnails)) {
            scope.launch { for (p in channel) send(p) }
        }
    }

    /** Queues one JSON object (or array) text for `POST /api/events`; returns immediately. */
    fun post(eventJson: String) {
        events.trySend(Post("/api/events", eventJson.toRequestBody(JSON)))
    }

    /** The tracks as drawn (`EventJson.frame`); only the latest waiting frame is sent. */
    fun postFrame(frameJson: String) {
        frames.trySend(Post("/api/frame", frameJson.toRequestBody(JSON)))
    }

    /** A small JPEG of the preview the boxes are measured in (optional, from the camera path). */
    fun postThumbnail(jpeg: ByteArray) {
        thumbnails.trySend(Post("/api/thumbnail", jpeg.toRequestBody(JPEG)))
    }

    /** Clears the dashboard's counts and list, alongside the phone's own reset; queued behind earlier events. */
    fun reset() {
        events.trySend(Post("/api/reset", "{}".toRequestBody(JSON)))
    }

    /** Stops the workers; queued posts are dropped. */
    fun close() {
        scope.cancel()
        events.close()
        frames.close()
        thumbnails.close()
    }

    private fun send(p: Post) {
        try {
            val request = Request.Builder().url(baseUrl.trimEnd('/') + p.path).post(p.body).build()
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) sent.incrementAndGet() else failed.incrementAndGet()
            }
        } catch (e: Exception) {
            if (failed.incrementAndGet() % 20 == 1L) Log.w(TAG, "dashboard unreachable (${e.javaClass.simpleName}), dropping")
        }
    }

    private companion object {
        const val TAG = "EventClient"
        const val TIMEOUT_MS = 500L
        val JSON = "application/json".toMediaType()
        val JPEG = "image/jpeg".toMediaType()
    }
}
