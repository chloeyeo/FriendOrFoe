package com.chloeyeo.friendorfoe.session

/**
 * The three outcomes of a challenge. FRIEND only when every check passed; FOE when any check failed (the reason
 * says which); UNKNOWN when nothing answered in the window. Silence is never FRIEND.
 */
enum class VerdictKind { FRIEND, FOE, UNKNOWN }
