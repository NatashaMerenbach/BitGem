package com.bitgem.colorcam.data.camera

import android.os.SystemClock

/**
 * Testable stand-in for `SystemClock.elapsedRealtime()`.
 *
 * The frame throttle in `ColorRepositoryImpl` depends on wall-clock time; injecting the clock
 * lets the unit tests drive it deterministically instead of sleeping.
 */
fun interface ElapsedTimeSource {
    fun nowMillis(): Long

    companion object {
        /** The production implementation. */
        val SYSTEM: ElapsedTimeSource = ElapsedTimeSource { SystemClock.elapsedRealtime() }
    }
}
