package dev.smsforwarder.ui

import dev.smsforwarder.core.StatusKind
import dev.smsforwarder.core.StatusLabel
import dev.smsforwarder.ui.main.DayKind
import dev.smsforwarder.ui.main.RowUi
import dev.smsforwarder.ui.main.groupByDay
import dev.smsforwarder.ui.main.startOfDay
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class DayGroupTest {
    private val zone = ZoneId.of("America/Havana")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val now = at(2026, 11, 5, 10, 0)

    private fun row(id: Long, receivedAt: Long) =
        RowUi(id = id, sender = "AAA", subId = null, receivedAt = receivedAt, preview = "", label = StatusLabel(StatusKind.QUEUED, 0, null))

    @Test fun startOfDayIsLocalMidnight() {
        assertEquals(at(2026, 11, 5, 0, 0), startOfDay(now, zone))
    }

    @Test fun groupsTodayYesterdayAndOlder() {
        val rows = listOf(
            row(1, at(2026, 11, 5, 9, 0)),
            row(2, at(2026, 11, 4, 23, 59)),
            row(3, at(2026, 11, 4, 0, 0)),
            row(4, at(2026, 11, 1, 12, 0)),
        )
        val groups = groupByDay(rows, now, zone)
        assertEquals(listOf(DayKind.TODAY, DayKind.YESTERDAY, DayKind.OTHER), groups.map { it.kind })
        assertEquals(listOf(listOf(1L), listOf(2L, 3L), listOf(4L)), groups.map { g -> g.rows.map { it.id } })
        assertEquals(listOf(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 1)), groups.map { it.day })
    }

    @Test fun emptyListHasNoGroups() {
        assertEquals(emptyList<Any>(), groupByDay(emptyList(), now, zone))
    }
}
