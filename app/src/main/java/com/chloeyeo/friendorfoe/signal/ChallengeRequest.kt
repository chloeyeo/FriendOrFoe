package com.chloeyeo.friendorfoe.signal

/**
 * One challenge the phone asks the gadget to run.
 * @property boxId the tapped track's id; the answer is attributed to this box
 * @property challengerId this observer's id (2 bytes on the wire), from Prefs
 * @property t unix time in seconds when the phone asked
 */
data class ChallengeRequest(val boxId: Int, val challengerId: Int, val t: Long)
