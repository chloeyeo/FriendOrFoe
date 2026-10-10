package com.chloeyeo.friendorfoe.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The runtime's one lane (pattern from `examples-0.6.4/android/shared/RuntimeLane.kt`): a single thread that runs
 * every ClikaRT load, detect and close in order, plus the coordinator's `tick` (`FofApplication.runTick`) and the
 * debug parity run. The CameraX analyzer has its own `camera-analysis` executor (D-02) and only hands a frame over
 * when the lane is idle. Nothing of the runtime runs on the main thread.
 */
object RuntimeLane {
    val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "clika-runtime-lane") }

    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
}
