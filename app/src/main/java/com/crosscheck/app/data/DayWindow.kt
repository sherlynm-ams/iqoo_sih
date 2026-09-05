package com.crosscheck.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Half-open [start, end) epoch-millis window for one local calendar day. */
data class DayWindow(val start: Long, val end: Long) {
    companion object {
        fun forDay(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DayWindow {
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            return DayWindow(start, end)
        }

        fun today(nowMillis: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): DayWindow =
            forDay(Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate(), zone)
    }
}
