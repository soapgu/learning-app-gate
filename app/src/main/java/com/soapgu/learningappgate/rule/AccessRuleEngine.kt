package com.soapgu.learningappgate.rule

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** 时间规则裁决结果。所有边界均为绝对时间点，展示时由调用方转换到设备时区。 */
sealed interface AccessDecision {
    data class Allowed(
        val closesAt: Instant,
        val remainingMs: Long,
    ) : AccessDecision

    data class OutsideAllowedWindow(
        val nextOpeningAt: Instant,
    ) : AccessDecision
}

/**
 * 纯时间规则内核。调用方显式提供当前时间和时区，内核不读取系统时钟，也不保存状态。
 */
class AccessRuleEngine {
    fun decide(policy: AccessPolicy, now: Instant, zoneId: ZoneId): AccessDecision {
        val localNow = now.atZone(zoneId)
        val date = localNow.toLocalDate()
        val time = localNow.toLocalTime()
        val window = policy.timeWindow

        return if (window.startInclusive < window.endExclusive) {
            decideSameDayWindow(window, date, time, now, zoneId)
        } else {
            decideOvernightWindow(window, date, time, now, zoneId)
        }
    }

    private fun decideSameDayWindow(
        window: DailyTimeWindow,
        date: LocalDate,
        time: LocalTime,
        now: Instant,
        zoneId: ZoneId,
    ): AccessDecision = when {
        time < window.startInclusive -> outside(date, window.startInclusive, now, zoneId)
        time < window.endExclusive -> allowed(date, window.endExclusive, now, zoneId)
        else -> outside(date.plusDays(1), window.startInclusive, now, zoneId)
    }

    private fun decideOvernightWindow(
        window: DailyTimeWindow,
        date: LocalDate,
        time: LocalTime,
        now: Instant,
        zoneId: ZoneId,
    ): AccessDecision = when {
        time >= window.startInclusive -> allowed(date.plusDays(1), window.endExclusive, now, zoneId)
        time < window.endExclusive -> allowed(date, window.endExclusive, now, zoneId)
        else -> outside(date, window.startInclusive, now, zoneId)
    }

    private fun allowed(
        boundaryDate: LocalDate,
        boundaryTime: LocalTime,
        now: Instant,
        zoneId: ZoneId,
    ): AccessDecision.Allowed {
        val closesAt = futureBoundary(boundaryDate, boundaryTime, now, zoneId)
        // Instant 支持纳秒，但公开接口使用毫秒；不足 1ms 的正剩余时间仍报告 1ms。
        val remainingMs = Duration.between(now, closesAt).toMillis().coerceAtLeast(1L)
        return AccessDecision.Allowed(closesAt, remainingMs)
    }

    private fun outside(
        boundaryDate: LocalDate,
        boundaryTime: LocalTime,
        now: Instant,
        zoneId: ZoneId,
    ): AccessDecision.OutsideAllowedWindow =
        AccessDecision.OutsideAllowedWindow(futureBoundary(boundaryDate, boundaryTime, now, zoneId))

    /**
     * 将本地墙上时间解析为严格晚于 now 的边界。重叠时间会检查两个 offset；跳时缺口
     * 中的本地时间采用 java.time 标准行为，按缺口长度向后平移。当天没有未来候选时，
     * 顺延到下一天同一边界。
     */
    private fun futureBoundary(
        initialDate: LocalDate,
        time: LocalTime,
        now: Instant,
        zoneId: ZoneId,
    ): Instant {
        var date = initialDate
        repeat(2) {
            val localDateTime = LocalDateTime.of(date, time)
            val offsets = zoneId.rules.getValidOffsets(localDateTime)
            val candidates = if (offsets.isEmpty()) {
                listOf(localDateTime.atZone(zoneId).toInstant())
            } else {
                offsets.map(localDateTime::toInstant)
            }
            candidates.filter { it > now }.minOrNull()?.let { return it }
            date = date.plusDays(1)
        }
        error("无法解析未来时间边界：date=$initialDate time=$time zoneId=$zoneId now=$now")
    }
}
