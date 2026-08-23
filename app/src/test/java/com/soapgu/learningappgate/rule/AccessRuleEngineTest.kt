package com.soapgu.learningappgate.rule

import com.soapgu.learningappgate.target.TargetApps
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessRuleEngineTest {
    private val engine = AccessRuleEngine()
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test
    fun defaultPolicy_centralizesDoubaoAndTimeWindow() {
        assertEquals(TargetApps.DOUBAO, AccessPolicy.DEFAULT.targetApp)
        assertEquals(LocalTime.of(7, 20), AccessPolicy.DEFAULT.timeWindow.startInclusive)
        assertEquals(LocalTime.of(20, 30), AccessPolicy.DEFAULT.timeWindow.endExclusive)
    }

    @Test
    fun sameDayWindow_enforcesInclusiveStartAndExclusiveEndAtMillisecondPrecision() {
        assertOutside("2026-08-23T07:19:59.999+08:00", "2026-08-22T23:20:00Z")
        assertAllowed("2026-08-23T07:20:00+08:00", "2026-08-23T12:30:00Z", 47_400_000L)
        assertAllowed("2026-08-23T20:29:59.999+08:00", "2026-08-23T12:30:00Z", 1L)
        assertOutside("2026-08-23T20:30:00+08:00", "2026-08-23T23:20:00Z")
    }

    @Test
    fun sameDayWindow_returnsTodayOrTomorrowOpening() {
        assertOutside("2026-08-23T06:00:00+08:00", "2026-08-22T23:20:00Z")
        assertOutside("2026-08-23T21:00:00+08:00", "2026-08-23T23:20:00Z")
    }

    @Test
    fun overnightWindow_handlesBothSidesOfMidnightAndBoundaries() {
        val policy = policy("20:30", "07:20")

        assertOutside(policy, "2026-08-23T20:29:59.999+08:00", "2026-08-23T12:30:00Z")
        assertAllowed(policy, "2026-08-23T20:30:00+08:00", "2026-08-23T23:20:00Z", 39_000_000L)
        assertAllowed(policy, "2026-08-23T23:59:59.999+08:00", "2026-08-23T23:20:00Z", 26_400_001L)
        assertAllowed(policy, "2026-08-24T00:00:00+08:00", "2026-08-23T23:20:00Z", 26_400_000L)
        assertAllowed(policy, "2026-08-24T07:19:59.999+08:00", "2026-08-23T23:20:00Z", 1L)
        assertOutside(policy, "2026-08-24T07:20:00+08:00", "2026-08-24T12:30:00Z")
    }

    @Test
    fun calendarTransitions_handleMonthYearAndLeapDay() {
        assertOutside("2026-01-31T21:00:00+08:00", "2026-01-31T23:20:00Z")
        assertOutside("2026-12-31T21:00:00+08:00", "2026-12-31T23:20:00Z")
        assertOutside("2028-02-28T21:00:00+08:00", "2028-02-28T23:20:00Z")

        val overnight = policy("20:30", "07:20")
        assertAllowed(overnight, "2028-02-29T23:00:00+08:00", "2028-02-29T23:20:00Z", 30_000_000L)
    }

    @Test
    fun sameInstant_canProduceDifferentDecisionsInDifferentZones() {
        val now = Instant.parse("2026-08-23T14:00:00Z")
        val shanghaiDecision = engine.decide(AccessPolicy.DEFAULT, now, shanghai)
        val newYorkDecision = engine.decide(AccessPolicy.DEFAULT, now, ZoneId.of("America/New_York"))

        assertTrue(shanghaiDecision is AccessDecision.OutsideAllowedWindow)
        assertTrue(newYorkDecision is AccessDecision.Allowed)
        assertNotEquals(shanghaiDecision, newYorkDecision)
    }

    @Test
    fun remainingTime_preservesNonWholeSecondMilliseconds() {
        assertAllowed("2026-08-23T20:29:58.123+08:00", "2026-08-23T12:30:00Z", 1_877L)
    }

    @Test
    fun daylightSavingGapAndOverlap_alwaysReturnFutureBoundaries() {
        val newYork = ZoneId.of("America/New_York")
        val gapPolicy = policy("01:00", "02:30")
        val beforeGap = Instant.parse("2026-03-08T06:30:00Z")
        val gapDecision = engine.decide(gapPolicy, beforeGap, newYork) as AccessDecision.Allowed
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), gapDecision.closesAt)
        assertTrue(gapDecision.closesAt > beforeGap)

        val overlapPolicy = policy("00:30", "01:30")
        val secondOccurrence = Instant.parse("2026-11-01T06:15:00Z")
        val overlapDecision = engine.decide(overlapPolicy, secondOccurrence, newYork) as AccessDecision.Allowed
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"), overlapDecision.closesAt)
        assertTrue(overlapDecision.closesAt > secondOccurrence)
    }

    @Test
    fun equalStartAndEnd_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DailyTimeWindow(LocalTime.NOON, LocalTime.NOON)
        }
    }

    @Test
    fun identicalInputs_areDeterministic() {
        val now = Instant.parse("2026-08-23T08:00:00Z")
        assertEquals(
            engine.decide(AccessPolicy.DEFAULT, now, shanghai),
            engine.decide(AccessPolicy.DEFAULT, now, shanghai),
        )
    }

    private fun policy(start: String, end: String) = AccessPolicy(
        targetApp = TargetApps.DOUBAO,
        timeWindow = DailyTimeWindow(LocalTime.parse(start), LocalTime.parse(end)),
    )

    private fun assertAllowed(input: String, closesAt: String, remainingMs: Long) =
        assertAllowed(AccessPolicy.DEFAULT, input, closesAt, remainingMs)

    private fun assertAllowed(policy: AccessPolicy, input: String, closesAt: String, remainingMs: Long) {
        val actual = engine.decide(policy, Instant.parse(input), shanghai)
        assertEquals(AccessDecision.Allowed(Instant.parse(closesAt), remainingMs), actual)
    }

    private fun assertOutside(input: String, nextOpeningAt: String) =
        assertOutside(AccessPolicy.DEFAULT, input, nextOpeningAt)

    private fun assertOutside(policy: AccessPolicy, input: String, nextOpeningAt: String) {
        val actual = engine.decide(policy, Instant.parse(input), shanghai)
        assertEquals(AccessDecision.OutsideAllowedWindow(Instant.parse(nextOpeningAt)), actual)
    }
}
