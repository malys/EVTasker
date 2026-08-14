package com.evsuite.tasker.update

import android.content.Context
/**
 * OTA is suspended while the suite safety and legal audit is open.
 * Keep this flavour seam inert until a reviewed change explicitly re-enables it.
 */
object UpdateHook {
    fun isSupported(): Boolean = false
    fun checkInBackground(@Suppress("UNUSED_PARAMETER") context: Context) = Unit
}
