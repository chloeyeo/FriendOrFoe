package com.chloeyeo.friendorfoe.ui.overlay

import com.chloeyeo.friendorfoe.detect.ObjClass
import com.chloeyeo.friendorfoe.session.TrackUi

/**
 * The drone track under a tap at ([x], [y]) in preview pixels, or null. Boxes are grown by [slop] pixels so a
 * small, far drone is still easy to hit; when boxes overlap the smallest one wins, since it is the more
 * specific target.
 */
fun droneAt(tracks: List<TrackUi>, x: Float, y: Float, slop: Float = 0f): TrackUi? =
    tracks
        .filter { it.cls == ObjClass.DRONE }
        .filter { t ->
            x >= t.box.left - slop && x <= t.box.right + slop &&
                y >= t.box.top - slop && y <= t.box.bottom + slop
        }
        .minByOrNull { it.box.area }
