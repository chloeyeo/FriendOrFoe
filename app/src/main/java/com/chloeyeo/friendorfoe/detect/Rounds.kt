package com.chloeyeo.friendorfoe.detect

import kotlin.math.max

/**
 * What one detection round runs: the drone model on the whole frame ([full]) or on a square crop around each box in
 * [crops] (frame pixels, the tracked drones), and the person model on the whole frame when [person] is set.
 */
data class RoundPlan(val full: Boolean, val crops: List<BoxF> = emptyList(), val person: Boolean = true) {
    /** `full` or `crop`, for the strip and the log. */
    val kind: String get() = if (full) "full" else "crop"

    companion object {
        /** Both models on the whole frame: what [Detector.detect] does. */
        val FULL = RoundPlan(full = true, person = true)
    }
}

/** A [Detector] that can run a [RoundPlan] (crop rounds, skipped person rounds); [DualDetector] in the app. */
interface RoundDetector : Detector {
    /** Runs [plan] on [frame] and returns the boxes in frame pixels, like [Detector.detect]. Blocking; runtime lane. */
    fun detectRound(frame: Frame, plan: RoundPlan): List<Detection>
}

/**
 * Decides each detection round (runtime lane, under the coordinator's lock):
 * - no tracked drone, more than [Config.maxCrops] of them, every [Config.fullEvery]-th round, or [Config.fullMaxGapMs]
 *   since the last full round ended: a FULL scan (so new drones are found);
 * - otherwise a CROP round on the tracked drone boxes;
 * - the person model every [Config.personEvery]-th round (the first round included), whatever the drone half does.
 *
 * [personStickyMs] is the person coast time the tracker needs so a person track survives the rounds that skip the
 * person model: 1.5 x the measured time between person rounds (until two exist: [Config.personEvery] x the measured
 * round interval), never below [Config.basePersonStickyMs]. Pure Kotlin, not thread-safe.
 */
class RoundScheduler(val config: Config = Config.current) {
    data class Config(
        val fullEvery: Int = 4,
        val fullMaxGapMs: Long = 3_000L,
        val personEvery: Int = 3,
        val maxCrops: Int = 3,
        val basePersonStickyMs: Long = 1_500L,
        val baseDroneStickyMs: Long = 1_000L,
        val maxDroneStickyMs: Long = 5_000L,
    ) {
        companion object {
            /** Read when a scheduler is built; the debug launch extras may set it before the coordinator exists. */
            @Volatile
            var current: Config = Config()
        }
    }

    private var round = 0L
    private var roundsSinceFull = 0
    private var lastFullEndMs: Long? = null
    private var lastRoundEndMs: Long? = null
    private var lastPersonEndMs: Long? = null
    private var roundIntervalMs: Long? = null
    private var personIntervalMs: Long? = null

    /** The plan for the round starting at [nowMs], given the tracked drone boxes [droneBoxes] (frame pixels). */
    fun plan(droneBoxes: List<BoxF>, nowMs: Long): RoundPlan {
        val person = round % max(1, config.personEvery) == 0L
        val lastFull = lastFullEndMs
        val full = droneBoxes.isEmpty() ||
            droneBoxes.size > config.maxCrops ||
            lastFull == null ||
            roundsSinceFull + 1 >= max(1, config.fullEvery) ||
            nowMs - lastFull >= config.fullMaxGapMs
        return if (full) RoundPlan(full = true, person = person) else RoundPlan(full = false, crops = droneBoxes, person = person)
    }

    /** Records that [plan] finished at [endMs] (wall clock); call once per round, after [plan]. */
    fun done(plan: RoundPlan, endMs: Long) {
        round++
        if (plan.full) {
            roundsSinceFull = 0
            lastFullEndMs = endMs
        } else {
            roundsSinceFull++
        }
        lastRoundEndMs?.let { roundIntervalMs = ewma(roundIntervalMs, endMs - it) }
        lastRoundEndMs = endMs
        if (plan.person) {
            lastPersonEndMs?.let { personIntervalMs = max(endMs - it, ewma(personIntervalMs, endMs - it)) }
            lastPersonEndMs = endMs
        }
    }

    /** How long a person track may coast without a person round; see the class KDoc. */
    fun personStickyMs(): Long {
        val interval = personIntervalMs ?: roundIntervalMs?.let { it * max(1, config.personEvery) } ?: return config.basePersonStickyMs
        return max(config.basePersonStickyMs, interval * 3 / 2)
    }

    /**
     * How long a drone track may coast without a detection: 2.5 x the measured round interval (about two missed
     * rounds), between [Config.baseDroneStickyMs] and [Config.maxDroneStickyMs]; the max before two rounds exist.
     */
    fun droneStickyMs(): Long {
        val interval = roundIntervalMs ?: return config.maxDroneStickyMs
        return (interval * 5 / 2).coerceIn(config.baseDroneStickyMs, config.maxDroneStickyMs)
    }

    private fun ewma(old: Long?, sample: Long): Long = if (old == null) sample else (old + sample) / 2
}
