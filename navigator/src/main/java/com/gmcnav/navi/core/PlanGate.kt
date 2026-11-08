package com.gmcnav.navi.core

/**
 * One route-panel session. Every new plan and every close of the panel bumps the token and cancels the search in flight
 * ([cancel], e.g. `router.gen++`); a background result is applied only while its token is still current, so a result that
 * arrives after the panel was closed (or after a newer plan started) is dropped.
 */
class PlanGate(private val cancel: () -> Unit) {
    @Volatile var token = 0; private set

    /** A new plan: older results become stale. */
    fun begin(): Int { cancel(); return ++token }

    /** The panel closed: every pending result becomes stale. */
    fun close() { cancel(); token++ }

    fun current(t: Int) = t == token
}
