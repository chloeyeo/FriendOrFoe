package com.chloeyeo.friendorfoe.ui.overlay

import com.chloeyeo.friendorfoe.detect.BoxF
import com.chloeyeo.friendorfoe.detect.ObjClass
import com.chloeyeo.friendorfoe.session.IffState
import com.chloeyeo.friendorfoe.session.TrackUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HitTestTest {
    private val person = TrackUi(1, ObjClass.PERSON, BoxF(100f, 100f, 900f, 900f), IffState.Unchallenged)
    private val bigDrone = TrackUi(2, ObjClass.DRONE, BoxF(400f, 200f, 800f, 600f), IffState.Unchallenged)
    private val smallDrone = TrackUi(3, ObjClass.DRONE, BoxF(500f, 300f, 600f, 400f), IffState.Unchallenged)
    private val tracks = listOf(person, bigDrone, smallDrone)

    @Test
    fun peopleAreNeverTargets() {
        assertNull(droneAt(tracks, 200f, 800f))
    }

    @Test
    fun smallestOverlappingDroneWins() {
        assertEquals(smallDrone, droneAt(tracks, 550f, 350f))
        assertEquals(bigDrone, droneAt(tracks, 450f, 250f))
    }

    @Test
    fun slopWidensTheTarget() {
        assertNull(droneAt(tracks, 820f, 500f))
        assertEquals(bigDrone, droneAt(tracks, 820f, 500f, slop = 30f))
    }
}
