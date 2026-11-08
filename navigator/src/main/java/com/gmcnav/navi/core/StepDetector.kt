package com.gmcnav.navi.core

import kotlin.math.sqrt

/**
 * Шаги по акселерометру (встроенный шагомер Samsung при медленной ходьбе с телефоном в руке пропускает 35–65 % шагов).
 * |a| − g → два ФНЧ 1-го порядка 3 Гц → автомат «впадина/пик» с гистерезисом 0.2 м/с²; шаг — пик ≥ 0.5 м/с²,
 * выше более высокой из соседних впадин на ≥ 0.6, не чаще 0.33 с; время шага сдвинуто на задержку фильтра (0.1 с).
 * На записи 27.10 (2082 шага по эталонному офлайн-фильтру): найдено 95 %, совпадают по времени (±0.15 с) 93–97 %.
 */
class StepDetector {
    private var init = false
    private var last = 0L
    private var f1 = 0.0
    private var f2 = 0.0
    private var rising = false
    private var valley = 0.0
    private var peak = 0.0
    private var peakT = 0L
    private var candT = 0L
    private var candPeak = 0.0
    private var candValley = 0.0
    private var hasCand = false
    private var lastStep = Long.MIN_VALUE / 2

    fun reset() { init = false; hasCand = false; rising = false; lastStep = Long.MIN_VALUE / 2 }

    /** Отсчёт акселерометра; возвращает время шага (нс, та же шкала) или 0. */
    fun add(t: Long, ax: Float, ay: Float, az: Float): Long {
        val x = sqrt((ax * ax + ay * ay + az * az).toDouble()) - 9.81
        if (!init) { init = true; last = t; f1 = x; f2 = x; valley = x; return 0 }
        val dt = (t - last) / 1e9; last = t
        if (dt <= 0 || dt > 0.2) return 0
        val a = dt / (RC + dt)
        f1 += a * (x - f1); f2 += a * (f1 - f2)
        val f = f2
        var step = 0L
        if (rising) {
            if (f > peak) { peak = f; peakT = t }
            else if (f < peak - HYST) { candT = peakT; candPeak = peak; candValley = valley; hasCand = true; rising = false; valley = f }
        } else {
            if (f < valley) valley = f
            else if (f > valley + HYST) {
                if (hasCand) {
                    hasCand = false
                    if (candPeak >= HEIGHT && candPeak - maxOf(candValley, valley) >= PROM && candT - lastStep >= MIN_GAP) {
                        lastStep = candT; step = candT - DELAY
                    }
                }
                rising = true; peak = f; peakT = t
            }
        }
        return step
    }

    companion object {
        private const val RC = 1.0 / (2 * Math.PI * 3.0)
        private const val HYST = 0.2
        private const val HEIGHT = 0.5
        private const val PROM = 0.6
        private const val MIN_GAP = 330_000_000L
        private const val DELAY = 100_000_000L
    }
}
