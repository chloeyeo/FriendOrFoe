package com.chloeyeo.friendorfoe.signal

/**
 * One challenge the phone asks the gadget to run.
 * @property boxId the tapped track's id; the answer is attributed to this box
 * @property challengerId this observer's id, from Prefs; not sent to Ihan's reader (its reader and drone ids are
 *   compile-time constants), kept for the event log and a later firmware
 * @property t unix time in seconds when the phone asked; not sent either (the reader has no clock or day key)
 */
data class ChallengeRequest(val boxId: Int, val challengerId: Int, val t: Long)
