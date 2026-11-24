package org.lior.gymbro.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    private val zone = ZoneId.of("Asia/Jerusalem")
    private fun t(y: Int, m: Int, d: Int, h: Int) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    private val stations = listOf(
        Station("a", "Arm curl", qrValue = "https://technogym.com/x", qrFormat = "QR_CODE", createdAt = 1),
        Station("b", "Bench", createdAt = 2, weightIncrement = 2.5, images = listOf("b1.jpg", "b2.jpg")),
        Station("c", "Old", createdAt = 3, deleted = true),
        Station("d", "Dumbbells", createdAt = 4),
    )
    private val uses = listOf(
        StationUse("u1", "a", t(2026, 11, 23, 18), 10, 30.0, 5, "Arm curl", "qr", t(2026, 11, 23, 17)),
        StationUse("u2", "a", t(2026, 11, 24, 18), 12, 35.0, 7, "Arm curl", "grid"),
        StationUse("u3", "a", t(2026, 11, 24, 19), 8, 40.0, 9),
        StationUse("u4", "b", t(2026, 11, 24, 19), 15, 20.0, 3),
    )

    @Test fun jsonRoundTrip() {
        val d = GymData(createdAt = 5, savedAt = 6, saveCount = 7, stations = stations, uses = uses)
        val back = JsonCodec.decode(JsonCodec.encode(d))
        assertEquals(d, back)
    }

    @Test fun jsonLenient() {
        val d = JsonCodec.decode("""{"stations":[{"id":"x","weightIncrement":-1}],"uses":[{"id":"u","stationId":"x"}]}""")
        assertEquals(Station.DEFAULT_INCREMENT, d.stations[0].weightIncrement)
        assertNull(d.stations[0].qrValue)
        assertEquals(0, d.uses[0].reps)
    }

    @Test fun launcherOrderPutsCodelessFirst() {
        assertEquals(listOf("b", "d", "a"), launcherOrder(stations).map { it.id })
    }

    @Test fun gridFitsEverything() {
        for (n in 1..60) {
            val g = GridFit.fit(n, 1000f, 1600f, 10f)
            assertTrue(g.cols * g.rows >= n, "n=$n")
            assertTrue(g.rows * g.cellH + (g.rows - 1) * 10f <= 1600.5f, "n=$n height")
            assertTrue(g.cols * g.cellW + (g.cols - 1) * 10f <= 1000.5f, "n=$n width")
            assertTrue(g.cols >= 2)
            assertTrue(g.cellH <= g.cellW + 0.01f)
        }
        // the draft: 8 stations, two columns
        assertEquals(2, GridFit.fit(8, 780f, 1100f, 30f).cols)
        // many stations: more columns
        assertTrue(GridFit.fit(40, 780f, 1100f, 10f).cols > 3)
    }

    @Test fun weights() {
        assertEquals("40", fmtWeight(40.0))
        assertEquals("12.5", fmtWeight(12.5))
        assertEquals("2.25", fmtWeight(2.25))
        assertEquals(0.3, stepWeight(0.1, 0.1, 2))
        assertEquals(0.0, stepWeight(2.5, 5.0, -1))
        assertEquals(2.5, parseIncrement("2,5"))
        assertNull(parseIncrement("0"))
        assertNull(parseIncrement("abc"))
    }

    @Test fun stats() {
        val now = t(2026, 11, 24, 20)
        assertEquals(mapOf("a" to 20, "b" to 15), Stats.repsTodayByStation(uses, now, zone))
        assertEquals("u3", Stats.lastUse(uses, "a")!!.id)
        val per = Stats.perStation(uses, zone).getValue("a")
        assertEquals(3, per.sets)
        assertEquals(40.0, per.bestWeight)
        assertEquals(listOf(LocalDate.of(2026, 11, 23) to 30.0, LocalDate.of(2026, 11, 24) to 40.0), per.maxWeightByDay)
        val days = Stats.lastDays(uses, now, zone, 7)
        assertEquals(7, days.size)
        assertEquals(3, days.last().sets)
        assertEquals(0, days.first().sets)
        assertEquals(2, Stats.streak(uses, now, zone))
        assertEquals(4, Stats.total(uses, zone, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)).sets)
    }
}
