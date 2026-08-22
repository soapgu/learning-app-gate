package com.soapgu.learningappgate.accessibility

import com.soapgu.learningappgate.target.TargetApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveWindowTargetPolicyTest {
    @Test
    fun classify_distinguishesTargetOtherAndUnknown() {
        assertEquals(
            ActiveWindowTargetState.Target,
            ActiveWindowTargetPolicy.classify(TargetApps.DOUBAO.packageName, TargetApps.DOUBAO),
        )
        assertEquals(
            ActiveWindowTargetState.Other,
            ActiveWindowTargetPolicy.classify("com.hihonor.android.launcher", TargetApps.DOUBAO),
        )
        assertEquals(
            ActiveWindowTargetState.Unknown,
            ActiveWindowTargetPolicy.classify(null, TargetApps.DOUBAO),
        )
    }

    @Test
    fun eventDrivenBackRefill_allowsUnknownButRejectsConfirmedOther() {
        assertTrue(ActiveWindowTargetPolicy.allowsEventDrivenBackRefill(ActiveWindowTargetState.Target))
        assertTrue(ActiveWindowTargetPolicy.allowsEventDrivenBackRefill(ActiveWindowTargetState.Unknown))
        assertFalse(ActiveWindowTargetPolicy.allowsEventDrivenBackRefill(ActiveWindowTargetState.Other))
    }

    @Test
    fun expiryInterception_requiresConfirmedTarget() {
        assertTrue(ActiveWindowTargetPolicy.allowsExpiryInterception(ActiveWindowTargetState.Target))
        assertFalse(ActiveWindowTargetPolicy.allowsExpiryInterception(ActiveWindowTargetState.Other))
        assertFalse(ActiveWindowTargetPolicy.allowsExpiryInterception(ActiveWindowTargetState.Unknown))
    }
}
