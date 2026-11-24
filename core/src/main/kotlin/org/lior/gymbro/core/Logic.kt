package org.lior.gymbro.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A grid of equal cells: [cols] x [rows], each [cellW] x [cellH]. */
data class Grid(val cols: Int, val rows: Int, val cellW: Float, val cellH: Float)

/**
 * The launcher shows every station on one screen (user, layout draft): the grid takes the column count that gives the
 * biggest cells, never fewer than [minCols] columns. A cell is never taller than it is wide.
 */
object GridFit {
    fun fit(n: Int, width: Float, height: Float, gap: Float, minCols: Int = 2): Grid {
        val cw0 = (width - gap * (minCols - 1)) / minCols
        if (n <= 0) return Grid(minCols, 0, cw0, 0f)
        var best = Grid(minCols, ceil(n / minCols.toDouble()).toInt(), cw0, 0f)
        var bestSide = -1f
        for (cols in minCols..max(minCols, n)) {
            val rows = ceil(n / cols.toDouble()).toInt()
            val cw = (width - gap * (cols - 1)) / cols
            val ch = (height - gap * (rows - 1)) / rows
            val side = min(cw, ch)
            if (side > bestSide + 0.01f) {
                bestSide = side
                best = Grid(cols, rows, cw, max(0f, min(ch, cw)))
            }
        }
        return best
    }
}

/** The order of the launcher's buttons: stations without a code first (user), each group in the order they were made. */
fun launcherOrder(stations: List<Station>): List<Station> =
    stations.filter { !it.deleted }.sortedWith(compareBy<Station>({ it.hasQr }, { it.createdAt }, { it.name }))

/** A weight as the user reads it: "40", "12.5", "2.25". */
fun fmtWeight(w: Double): String {
    if (w.isNaN() || w.isInfinite()) return "?"
    return BigDecimal(w).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().let {
        if (it.scale() < 0) it.setScale(0) else it
    }.toPlainString()
}

/** A weight stepped by the machine's increment, never below 0, rid of floating-point dust. */
fun stepWeight(w: Double, increment: Double, steps: Int): Double {
    val v = w + increment * steps
    return max(0.0, BigDecimal(v).setScale(3, RoundingMode.HALF_UP).toDouble())
}

/** Parses the increment the user typed ("2,5" or "2.5"); null when it is not a positive number. */
fun parseIncrement(text: String): Double? {
    val v = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    return if (v > 0 && !v.isInfinite()) v else null
}

data class DaySummary(val date: LocalDate, val sets: Int, val reps: Int, val volume: Double, val stations: Int)

data class StationSummary(
    val stationId: String,
    val sets: Int,
    val totalReps: Int,
    val totalVolume: Double,
    val bestWeight: Double,
    val firstTime: Long,
    val lastUse: StationUse?,
    /** The heaviest weight of each day the station was used, oldest first. */
    val maxWeightByDay: List<Pair<LocalDate, Double>>,
    val daysUsed: Int,
)

object Stats {
    fun dayOf(time: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()

    fun volume(u: StationUse): Double = u.reps * u.weight

    /** Reps done today on each station (the launcher's badges). */
    fun repsTodayByStation(uses: List<StationUse>, now: Long, zone: ZoneId): Map<String, Int> {
        val today = dayOf(now, zone)
        return uses.filter { dayOf(it.time, zone) == today }.groupBy { it.stationId }.mapValues { e -> e.value.sumOf { it.reps } }
    }

    fun lastUse(uses: List<StationUse>, stationId: String): StationUse? =
        uses.filter { it.stationId == stationId }.maxByOrNull { it.time }

    fun daily(uses: List<StationUse>, zone: ZoneId): List<DaySummary> =
        uses.groupBy { dayOf(it.time, zone) }.map { (d, us) ->
            DaySummary(d, us.size, us.sumOf { it.reps }, us.sumOf { volume(it) }, us.map { it.stationId }.distinct().size)
        }.sortedBy { it.date }

    /** The last [n] days up to today, days without training included (zeros). */
    fun lastDays(uses: List<StationUse>, now: Long, zone: ZoneId, n: Int): List<DaySummary> {
        val byDay = daily(uses, zone).associateBy { it.date }
        val today = dayOf(now, zone)
        return (n - 1 downTo 0).map { k -> today.minusDays(k.toLong()).let { d -> byDay[d] ?: DaySummary(d, 0, 0, 0.0, 0) } }
    }

    /** Totals of the days from [from] to [to], both included. */
    fun total(uses: List<StationUse>, zone: ZoneId, from: LocalDate, to: LocalDate): DaySummary {
        val us = uses.filter { dayOf(it.time, zone).let { d -> !d.isBefore(from) && !d.isAfter(to) } }
        return DaySummary(to, us.size, us.sumOf { it.reps }, us.sumOf { volume(it) }, us.map { it.stationId }.distinct().size)
    }

    fun perStation(uses: List<StationUse>, zone: ZoneId): Map<String, StationSummary> =
        uses.groupBy { it.stationId }.mapValues { (id, us) ->
            val byDay = us.groupBy { dayOf(it.time, zone) }
            StationSummary(
                stationId = id,
                sets = us.size,
                totalReps = us.sumOf { it.reps },
                totalVolume = us.sumOf { volume(it) },
                bestWeight = us.maxOf { it.weight },
                firstTime = us.minOf { it.time },
                lastUse = us.maxByOrNull { it.time },
                maxWeightByDay = byDay.map { (d, l) -> d to l.maxOf { it.weight } }.sortedBy { it.first },
                daysUsed = byDay.size,
            )
        }

    /** Distinct training days, and the current streak of consecutive training days (today or yesterday counts). */
    fun streak(uses: List<StationUse>, now: Long, zone: ZoneId): Int {
        val days = uses.map { dayOf(it.time, zone) }.toSet()
        var d = dayOf(now, zone)
        if (d !in days) d = d.minusDays(1)
        var n = 0
        while (d in days) { n++; d = d.minusDays(1) }
        return n
    }
}
