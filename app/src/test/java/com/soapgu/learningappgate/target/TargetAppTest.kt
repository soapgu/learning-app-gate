package com.soapgu.learningappgate.target

import org.junit.Assert.assertEquals
import org.junit.Test

class TargetAppTest {
    @Test
    fun doubao_usesPackageNameConfirmedOnDevice() {
        assertEquals("com.larus.nova", TargetApps.DOUBAO.packageName)
        assertEquals("豆包", TargetApps.DOUBAO.displayName)
    }
}
