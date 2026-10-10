package com.chloeyeo.friendorfoe.net

import com.chloeyeo.friendorfoe.session.EventUi
import org.junit.Assert.assertEquals
import org.junit.Test

class EventDiffTest {
    private val a = EventUi(1, "person detected · box 1")
    private val b = EventUi(2, "drone detected · box 2")
    private val c = EventUi(3, "challenge sent · box 2")

    @Test
    fun firstPostSendsEverythingOldestFirst() {
        assertEquals(EventDiff.Plan(false, listOf(a, b)), EventDiff.plan(listOf(b, a), lastSent = null))
    }

    @Test
    fun onlyRowsAboveTheLastSentOneAreNew() {
        assertEquals(EventDiff.Plan(false, listOf(c)), EventDiff.plan(listOf(c, b, a), lastSent = b))
        assertEquals(EventDiff.Plan(false, emptyList<EventUi>()), EventDiff.plan(listOf(c, b, a), lastSent = c))
    }

    @Test
    fun aClearedLogResetsTheDashboard() {
        assertEquals(EventDiff.Plan(true, emptyList<EventUi>()), EventDiff.plan(emptyList(), lastSent = c))
        assertEquals(EventDiff.Plan(true, listOf(a)), EventDiff.plan(listOf(a), lastSent = c))
    }
}
