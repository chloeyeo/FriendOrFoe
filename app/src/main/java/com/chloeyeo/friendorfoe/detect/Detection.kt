package com.chloeyeo.friendorfoe.detect

/** One detected object: its class, the detector's score (0..1) and the box in frame pixels. */
data class Detection(val cls: ObjClass, val score: Float, val box: BoxF)
