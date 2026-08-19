package com.soapgu.learningappgate.accessibility

import com.soapgu.learningappgate.target.TargetApps
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetAppEventMatcherTest {
    @Test
    fun matches_doubaoPackage() {
        assertTrue(TargetAppEventMatcher.matches("com.larus.nova", TargetApps.DOUBAO))
    }

    @Test
    fun doesNotMatch_otherOrMissingPackage() {
        assertFalse(TargetAppEventMatcher.matches("com.example.other", TargetApps.DOUBAO))
        assertFalse(TargetAppEventMatcher.matches(null, TargetApps.DOUBAO))
    }
}

