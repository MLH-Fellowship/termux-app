package com.termux.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Vibrator

class BellUtil private constructor(vibrator: Vibrator?) {
    private val handler = Handler(Looper.getMainLooper())
    private val bellRunnable: Runnable
    private var lastBell: Long = 0

    @Synchronized
    fun doBell() {
        val now = now()
        val timeSinceLastBell = now - lastBell
        if (timeSinceLastBell < 0) {
            // there is a next bell pending; don't schedule another one
        } else if (timeSinceLastBell < MIN_PAUSE) {
            // there was a bell recently, scheudle the next one
            handler.postDelayed(bellRunnable, MIN_PAUSE - timeSinceLastBell)
            lastBell = lastBell + MIN_PAUSE
        } else {
            // the last bell was long ago, do it now
            bellRunnable.run()
            lastBell = now
        }
    }

    private fun now(): Long {
        return SystemClock.uptimeMillis()
    }

    companion object {
        private val lock = Any()
        private const val DURATION: Long = 50
        private const val MIN_PAUSE = 3 * DURATION
        private var instance: BellUtil? = null
        fun getInstance(context: Context): BellUtil? {
            if (instance == null) {
                synchronized(lock) {
                    if (instance == null) {
                        instance = BellUtil(context.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
                    }
                }
            }
            return instance
        }
    }

    init {
        bellRunnable = Runnable { vibrator?.vibrate(DURATION) }
    }
}
