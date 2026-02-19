package com.tudorc.mediabus.util

import android.util.Log

object ServerLogger {
    private const val TAG = "MediaBusServer"

    fun d(component: String, message: String) {
        Log.d(TAG, "[$component] $message")
    }

    fun i(component: String, message: String) {
        Log.i(TAG, "[$component] $message")
    }

    fun w(component: String, message: String) {
        Log.w(TAG, "[$component] $message")
    }

    fun e(component: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.e(TAG, "[$component] $message")
        } else {
            Log.e(TAG, "[$component] $message", throwable)
        }
    }
}
