package dev.eclipse.ssh.ssh

/**
 * One reading of the two clocks Android keeps, taken either side of a wait.
 *
 * [uptimeMs] stops while the device is suspended; [elapsedRealtimeMs] does not. The difference between
 * the two over the same interval is therefore how long the device spent asleep inside it - which is the
 * one thing a deadline on this platform cannot tell you by itself.
 */
internal data class ClockReading(val elapsedRealtimeMs: Long, val uptimeMs: Long)

/** How a wait was actually spent. */
internal data class WaitSlice(val awakeMs: Long, val sleptMs: Long)

/**
 * What a wait that ended without the network coming back is allowed to mean.
 */
internal enum class HoldDecision {
    /** The device was awake for some of it and the network still did not return. Keep waiting. */
    KEEP_WAITING,

    /** The device was suspended. The deadline expired without anyone watching, so it proves nothing. */
    DEVICE_SLEPT,

    /** The time was spent awake and the network is still gone. The hold is over. */
    OUTAGE_EXPIRED,
}

/**
 * Below this, a gap between the clocks is an ordinary scheduling hiccup rather than a suspension, and
 * treating it as one would only make the app wait longer. Above it, the wait is assumed not to have
 * happened: the cost of that assumption is a session held too long, against a session dropped while the
 * user was walking to their desk with the phone in their pocket.
 */
internal const val SUSPENSION_MIN_MS = 2_000L

/**
 * Splits a wait into the part the device was awake for and the part it was not.
 *
 * Negative readings are clamped rather than trusted: the clocks come from a device whose time can be
 * reset under the app, and a negative [WaitSlice.awakeMs] would *extend* a hold instead of expiring it,
 * which is a loop rather than a wait.
 */
internal fun waitSlice(before: ClockReading, after: ClockReading): WaitSlice {
    val elapsedMs = (after.elapsedRealtimeMs - before.elapsedRealtimeMs).coerceAtLeast(0L)
    val awakeMs = (after.uptimeMs - before.uptimeMs).coerceAtLeast(0L)
    return WaitSlice(awakeMs = awakeMs, sleptMs = (elapsedMs - awakeMs).coerceAtLeast(0L))
}

/**
 * What to do with a hold after a wait that the network did not interrupt.
 *
 * A suspension outranks an expiry in the same slice, because both are true at once on a device that
 * slept and then woke: the deadline the slice reports as passed is a deadline nobody was awake to see
 * pass, and the network may well have been there throughout.
 */
internal fun holdDecision(
    remainingMs: Long,
    slice: WaitSlice,
    sleepThresholdMs: Long = SUSPENSION_MIN_MS,
): HoldDecision = when {
    slice.sleptMs >= sleepThresholdMs -> HoldDecision.DEVICE_SLEPT
    slice.awakeMs >= remainingMs -> HoldDecision.OUTAGE_EXPIRED
    else -> HoldDecision.KEEP_WAITING
}
