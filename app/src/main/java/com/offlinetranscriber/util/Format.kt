package com.offlinetranscriber.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun bytes(b: Long): String {
        if (b < 0) return "?"
        val kb = 1024.0
        return when {
            b < kb -> "$b B"
            b < kb * kb -> String.format(Locale.US, "%.0f KB", b / kb)
            b < kb * kb * kb -> String.format(Locale.US, "%.0f MB", b / (kb * kb))
            else -> String.format(Locale.US, "%.2f GB", b / (kb * kb * kb))
        }
    }

    fun duration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec / 60) % 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun eta(ms: Long): String {
        val sec = ms / 1000
        return when {
            sec < 60 -> "less than a minute"
            sec < 3600 -> "about ${(sec + 30) / 60} min"
            else -> String.format(Locale.US, "about %d h %02d min", sec / 3600, (sec / 60) % 60)
        }
    }

    fun date(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
}
