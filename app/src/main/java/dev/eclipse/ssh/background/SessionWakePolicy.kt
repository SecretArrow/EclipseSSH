package dev.eclipse.ssh.background

/**
 * Whether the app should be holding the CPU awake.
 */
internal enum class WakeNeed {
    NONE,

    /** A partial lock: the CPU stays up, the screen does not. */
    PARTIAL,
}

/**
 * When to hold a partial wakelock, and when to let go of it.
 *
 * The permission has been declared since the first release and never used, which is how a session that
 * is healthy with the phone in hand becomes one the platform may suspend the moment the screen goes
 * off: with no lock, a suspended device runs no code, answers no keepalive, and a liveness probe
 * against it comes back empty for a session that was never tested. The lock costs battery, so it is
 * held for exactly one situation - a live session on an app the user is not looking at, on a service
 * that is not already shutting down.
 */
internal fun wakeNeed(appVisible: Boolean, liveSessions: Int, stopping: Boolean): WakeNeed = when {
    stopping -> WakeNeed.NONE
    liveSessions <= 0 -> WakeNeed.NONE
    appVisible -> WakeNeed.NONE
    else -> WakeNeed.PARTIAL
}
