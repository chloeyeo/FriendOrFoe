package com.chloeyeo.friendorfoe.track

import com.chloeyeo.friendorfoe.detect.BoxF
import com.chloeyeo.friendorfoe.detect.Detection
import com.chloeyeo.friendorfoe.detect.ObjClass

/**
 * Greedy IoU matching with stable ids. On every update the pairs (live track, new detection) of the same class
 * with IoU at or above [iouThreshold] are matched best-first; a matched track keeps its id and takes the new box;
 * an unmatched detection starts a new track with the next id; an unmatched track coasts (keeps its last box), so a
 * box that flickers keeps its ring. Two coasting rules, one per overload:
 * - `update(detections)` (legacy, frame count): a track is dropped after more than [maxMisses] consecutive misses;
 * - `update(detections, nowMillis)` (the runtime lane, wall clock, D-09): a track is dropped once more than
 *   [droneStickyMs] (DRONE, 5 s) or [personStickyMs] (PERSON, 1.5 s) have passed since it was last seen, so the
 *   window is the same at 0.5 fps and at 2 fps.
 * Not thread-safe: call it from the runtime lane only (or under the owner's lock).
 */
class Tracker(
    private val iouThreshold: Float = 0.3f,
    private val maxMisses: Int = 3,
    private val droneStickyMs: Long = 5_000L,
    private val personStickyMs: Long = 1_500L,
) {

    /**
     * One tracked object; [misses] is 0 when it was seen in the latest update; [lastSeenMillis] is the wall-clock
     * time of the update that last matched or created it (0 for the frame-count overload).
     */
    data class Track(
        val id: Int,
        val cls: ObjClass,
        val box: BoxF,
        val score: Float,
        val misses: Int = 0,
        val lastSeenMillis: Long = 0L,
    )

    private var nextId = 1
    private val live = ArrayList<Track>()

    /** Every live track, oldest first, including the ones coasting through a miss. */
    val tracks: List<Track> get() = live.toList()

    /**
     * Feeds one frame's [detections] and returns the tracks seen in this frame (matched or new), oldest first.
     * Coasting by frame count ([maxMisses]).
     */
    fun update(detections: List<Detection>): List<Track> =
        match(detections, null) { it.misses + 1 <= maxMisses }

    /**
     * Feeds one frame's [detections] taken at [nowMillis] (wall clock) and returns the tracks seen in this frame
     * (matched or new), oldest first. Coasting by wall clock per class (D-09); a track whose id is in [pinned] (the
     * box locked for a challenge, the boxes still showing a verdict) never times out, so the next detection of the
     * same object re-matches it by IoU instead of starting a second ring beside it.
     */
    fun update(detections: List<Detection>, nowMillis: Long, pinned: Set<Int> = emptySet()): List<Track> =
        match(detections, nowMillis) { it.id in pinned || nowMillis - it.lastSeenMillis <= stickyMs(it.cls) }

    private fun stickyMs(cls: ObjClass): Long = when (cls) {
        ObjClass.DRONE -> droneStickyMs
        ObjClass.PERSON -> personStickyMs
    }

    private fun match(detections: List<Detection>, nowMillis: Long?, survives: (Track) -> Boolean): List<Track> {
        val seenAt = nowMillis ?: 0L
        val candidates = ArrayList<Triple<Float, Int, Int>>() // iou, track index, detection index
        for ((ti, track) in live.withIndex()) {
            for ((di, det) in detections.withIndex()) {
                if (det.cls != track.cls) continue
                val iou = track.box.iou(det.box)
                if (iou >= iouThreshold) candidates += Triple(iou, ti, di)
            }
        }
        candidates.sortByDescending { it.first }

        val trackTaken = BooleanArray(live.size)
        val detectionTaken = BooleanArray(detections.size)
        val updated = arrayOfNulls<Track>(live.size)
        for ((_, ti, di) in candidates) {
            if (trackTaken[ti] || detectionTaken[di]) continue
            trackTaken[ti] = true
            detectionTaken[di] = true
            val det = detections[di]
            updated[ti] = live[ti].copy(box = det.box, score = det.score, misses = 0, lastSeenMillis = seenAt)
        }

        val next = ArrayList<Track>(live.size + detections.size)
        for ((ti, track) in live.withIndex()) {
            val u = updated[ti]
            if (u != null) {
                next += u
            } else if (survives(track)) {
                next += track.copy(misses = track.misses + 1)
            }
        }
        for ((di, det) in detections.withIndex()) {
            if (detectionTaken[di]) continue
            next += Track(id = nextId++, cls = det.cls, box = det.box, score = det.score, lastSeenMillis = seenAt)
        }
        live.clear()
        live.addAll(next)
        return live.filter { it.misses == 0 }
    }

    /**
     * Moves live track [id] to [box] without touching its id, score, misses or last-seen time (the 10 fps
     * [MotionTracker] keeps the boxes current between detections, so the next IoU match compares like with like).
     * Unknown ids are ignored.
     */
    fun moveTo(id: Int, box: BoxF) {
        val i = live.indexOfFirst { it.id == id }
        if (i >= 0) live[i] = live[i].copy(box = box)
    }

    /** Forgets every track; ids keep counting up so an old ring is never confused with a new object. */
    fun reset() {
        live.clear()
    }
}
