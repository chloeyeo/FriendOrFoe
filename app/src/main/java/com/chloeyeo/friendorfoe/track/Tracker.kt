package com.chloeyeo.friendorfoe.track

import com.chloeyeo.friendorfoe.detect.BoxF
import com.chloeyeo.friendorfoe.detect.Detection
import com.chloeyeo.friendorfoe.detect.ObjClass

/**
 * Greedy IoU matching with stable ids. On every [update] the pairs (live track, new detection) of the same class
 * with IoU at or above [iouThreshold] are matched best-first; a matched track keeps its id and takes the new box;
 * an unmatched detection starts a new track with the next id; an unmatched track coasts (keeps its last box) and
 * is dropped after more than [maxMisses] consecutive misses, so a box that flickers for a frame keeps its ring.
 * Not thread-safe: call it from the runtime lane only.
 */
class Tracker(private val iouThreshold: Float = 0.3f, private val maxMisses: Int = 3) {

    /** One tracked object; [misses] is 0 when it was seen in the latest update. */
    data class Track(val id: Int, val cls: ObjClass, val box: BoxF, val score: Float, val misses: Int = 0)

    private var nextId = 1
    private val live = ArrayList<Track>()

    /** Every live track, oldest first, including the ones coasting through a miss. */
    val tracks: List<Track> get() = live.toList()

    /**
     * Feeds one frame's [detections] and returns the tracks seen in this frame (matched or new), oldest first.
     */
    fun update(detections: List<Detection>): List<Track> {
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
            updated[ti] = live[ti].copy(box = det.box, score = det.score, misses = 0)
        }

        val next = ArrayList<Track>(live.size + detections.size)
        for ((ti, track) in live.withIndex()) {
            val u = updated[ti]
            if (u != null) {
                next += u
            } else if (track.misses + 1 <= maxMisses) {
                next += track.copy(misses = track.misses + 1)
            }
        }
        for ((di, det) in detections.withIndex()) {
            if (detectionTaken[di]) continue
            next += Track(id = nextId++, cls = det.cls, box = det.box, score = det.score)
        }
        live.clear()
        live.addAll(next)
        return live.filter { it.misses == 0 }
    }

    /** Forgets every track; ids keep counting up so an old ring is never confused with a new object. */
    fun reset() {
        live.clear()
    }
}
