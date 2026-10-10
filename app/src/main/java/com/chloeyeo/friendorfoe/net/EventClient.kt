package com.chloeyeo.friendorfoe.net

/**
 * TODO(stream 1 / Chloe): fire-and-forget delivery of detection and verdict events to the laptop dashboard
 * (`laptop/dashboard/app.py`, `POST {baseUrl}/api/events`, JSON `{t, class, box, iffStatus, droneId, reason}`).
 *
 * Plan: one OkHttp client with a 500 ms call timeout; a bounded queue (about 64 events, drop the oldest when
 * full) drained by one background thread so the runtime lane never blocks on the network; failures are counted
 * for the status strip, never thrown, and nothing is retried — the dashboard is a side-car, not the demo.
 * `android:usesCleartextTraffic="true"` in the manifest is what allows the plain `http://` LAN target.
 *
 * @param baseUrl e.g. `http://192.168.1.2:8081` (dashboard on 8081: 8080 is taken on the integration lead's laptop)
 */
class EventClient(private val baseUrl: String) {
    /** Queues one JSON object text for delivery; returns immediately. */
    fun post(eventJson: String): Unit = throw NotImplementedError("stream 1: fire-and-forget POST /api/events")

    /** Stops the worker; queued events are dropped. */
    fun close(): Unit = throw NotImplementedError("stream 1: stop the queue worker")
}
