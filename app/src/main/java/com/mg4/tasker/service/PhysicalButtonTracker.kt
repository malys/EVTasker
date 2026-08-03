package com.mg4.tasker.service

import com.mg4.hardware.catalog.SnapshotKeys

/** Converts the SAIC hard-key down/long/release stream into one rule event per press. */
internal class PhysicalButtonTracker {

    enum class Button { STAR_LEFT, STAR_RIGHT }
    enum class Press { SHORT, LONG }
    data class Event(val button: Button, val press: Press) {
        fun readings(): Map<String, Any> = mapOf(
            SnapshotKeys.KEY_STAR_LEFT_SHORT to (button == Button.STAR_LEFT && press == Press.SHORT),
            SnapshotKeys.KEY_STAR_LEFT_LONG to (button == Button.STAR_LEFT && press == Press.LONG),
            SnapshotKeys.KEY_STAR_RIGHT_SHORT to (button == Button.STAR_RIGHT && press == Press.SHORT),
            SnapshotKeys.KEY_STAR_RIGHT_LONG to (button == Button.STAR_RIGHT && press == Press.LONG)
        )
    }

    private enum class State { DOWN, LONG_REPORTED }
    private val states = mutableMapOf<Button, State>()

    fun accept(keyCode: Int, down: Boolean, longPress: Boolean): Event? {
        val button = buttonFor(keyCode) ?: return null
        return when {
            down && longPress && states[button] != State.LONG_REPORTED -> {
                states[button] = State.LONG_REPORTED
                Event(button, Press.LONG)
            }
            down -> {
                states.putIfAbsent(button, State.DOWN)
                null
            }
            states.remove(button) == State.DOWN -> Event(button, Press.SHORT)
            else -> null // release after a long press, duplicate release, or orphan release
        }
    }

    companion object {
        const val STAR_LEFT = 17
        const val STAR_RIGHT = 286
        const val STAR_RIGHT_ALT = 18

        private fun buttonFor(keyCode: Int): Button? = when (keyCode) {
            STAR_LEFT -> Button.STAR_LEFT
            STAR_RIGHT, STAR_RIGHT_ALT -> Button.STAR_RIGHT
            else -> null
        }
    }
}
