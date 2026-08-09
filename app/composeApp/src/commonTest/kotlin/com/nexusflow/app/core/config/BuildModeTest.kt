package com.nexusflow.app.core.config

import kotlin.test.Test
import kotlin.test.assertEquals

class BuildModeTest {
    @Test
    fun mapsDebugFlagsToTheMatchingBuildMode() {
        assertEquals(BuildMode.DEBUG, BuildMode.from(true))
        assertEquals(BuildMode.RELEASE, BuildMode.from(false))
    }
}
