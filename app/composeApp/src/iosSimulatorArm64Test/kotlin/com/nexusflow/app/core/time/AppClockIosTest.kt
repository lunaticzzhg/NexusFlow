package com.nexusflow.app.core.time

import kotlin.test.Test
import kotlin.test.assertEquals

class AppClockIosTest {
    @Test
    fun epochSecondsToMillisPreservesMilliseconds() {
        assertEquals(1_234L, epochSecondsToMillis(1.234))
    }
}
