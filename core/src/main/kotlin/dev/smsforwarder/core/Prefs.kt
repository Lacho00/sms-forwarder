package dev.smsforwarder.core

enum class SweepInterval(val minutes: Long) { M15(15), M30(30), M60(60) }

enum class StuckAfter(val hours: Long) {
    H1(1), H6(6), H24(24);

    val millis: Long get() = hours * 3_600_000L
}

enum class Retention(val days: Long?) {
    D30(30), D90(90), D365(365), FOREVER(null);

    fun cutoff(now: Long): Long? = days?.let { now - it * 86_400_000L }
}

enum class Language(val tag: String?) { SYSTEM(null), ES("es"), EN("en") }

enum class NotifyKind { RECOVERED, STUCK, PERMISSIONS }

enum class StatusFilter { ALL, QUEUED, ERROR }
