package com.gmcnav.navi

import java.util.concurrent.ForkJoinPool
import java.util.stream.Collectors

/**
 * Replay tests on all cores: independent runs (part × seed × config — each its own engine, seed and radio map copy:
 * RadioMap.match writes scores into the shared fingerprints, so one copy per thread) in a work-stealing pool, results in input
 * order, so reports and numbers are the same as sequential. Nested calls are fine (the pool steals work while joining).
 */
object Par {
    private val pool = ForkJoinPool(Runtime.getRuntime().availableProcessors())
    fun <T, R> map(items: List<T>, f: (T) -> R): List<R> =
        if (items.size <= 1) items.map(f) else pool.submit<List<R>> { items.parallelStream().map { f(it) }.collect(Collectors.toList()) }.get()
}
