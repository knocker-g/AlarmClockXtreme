package com.sysadmindoc.alarmclock.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sysadmindoc.alarmclock.R

object TimeFormatter {
    @Composable
    fun formatSeconds(totalSeconds: Int): String {
        if (totalSeconds == 0) return stringResource(R.string.settings_off)
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return when {
            m == 0 -> stringResource(R.string.time_unit_s, s)
            s == 0 -> stringResource(R.string.time_unit_m, m)
            else -> stringResource(R.string.time_unit_m_s, m, s)
        }
    }

    fun formatSeconds(context: Context, totalSeconds: Int): String {
        if (totalSeconds == 0) return context.getString(R.string.settings_off)
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return when {
            m == 0 -> context.getString(R.string.time_unit_s, s)
            s == 0 -> context.getString(R.string.time_unit_m, m)
            else -> context.getString(R.string.time_unit_m_s, m, s)
        }
    }

    @Composable
    fun formatHoursMinutes(hours: Int, minutes: Int): String {
        return when {
            hours > 0 && minutes > 0 -> stringResource(R.string.time_unit_h_m, hours, minutes)
            hours > 0 -> stringResource(R.string.time_unit_h, hours)
            else -> stringResource(R.string.time_unit_m, minutes)
        }
    }

    fun formatHoursMinutes(context: Context, hours: Int, minutes: Int): String {
        return when {
            hours > 0 && minutes > 0 -> context.getString(R.string.time_unit_h_m, hours, minutes)
            hours > 0 -> context.getString(R.string.time_unit_h, hours)
            else -> context.getString(R.string.time_unit_m, minutes)
        }
    }

    fun formatTimeLabel(context: Context, h: Int, m: Int, s: Int): String {
        return when {
            h > 0 && m > 0 && s > 0 -> context.getString(R.string.time_unit_h_m_s, h, m, s)
            h > 0 && m > 0 -> context.getString(R.string.time_unit_h_m, h, m)
            h > 0 && s > 0 -> context.getString(R.string.time_unit_h_m_s, h, 0, s) // Fallback
            m > 0 && s > 0 -> context.getString(R.string.time_unit_m_s, m, s)
            h > 0 -> context.getString(R.string.time_unit_h, h)
            m > 0 -> context.getString(R.string.time_unit_m, m)
            else -> context.getString(R.string.time_unit_s, s)
        }
    }
}
