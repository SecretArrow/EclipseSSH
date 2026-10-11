package dev.eclipse.ssh.background

/**
 * Which foreground-service type to ask for.
 */
internal enum class ForegroundNeed {
    /** What the service was written for: fetching and pushing in the background. */
    DATA_SYNC,

    /**
     * For work with no platform budget: an interactive terminal session that has to stay reachable.
     * The manifest's subtype property is what says so to the platform.
     */
    SPECIAL_USE,

    /** Nothing left that needs the process alive. */
    STOP,
}

/**
 * Which type keeps this service alive, given what it still has to do.
 *
 * `dataSync` is the honest type for a transfer and the wrong one for a terminal, because the platform
 * budgets it at six hours and then refuses to promote it again. When that budget ran out the service
 * promoted nothing, stopped itself and left every session held by nobody - the budget's own failure
 * mode applied to sessions the budget was never about. An exhausted budget is therefore a reason to
 * change type, not a reason to end anything; only having nothing left to keep alive is that.
 */
internal fun foregroundNeed(
    budgetExhausted: Boolean,
    liveSessions: Int,
    transferInFlight: Boolean,
): ForegroundNeed = when {
    liveSessions > 0 || transferInFlight ->
        if (budgetExhausted) ForegroundNeed.SPECIAL_USE else ForegroundNeed.DATA_SYNC
    else -> ForegroundNeed.STOP
}
