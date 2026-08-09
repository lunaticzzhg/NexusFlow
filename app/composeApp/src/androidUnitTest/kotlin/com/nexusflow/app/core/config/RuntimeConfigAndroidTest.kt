package com.nexusflow.app.core.config

import com.nexusflow.app.BuildConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeConfigAndroidTest {
    @Test
    fun readsTheCurrentAndroidBuildVariantMode() {
        assertEquals(BuildMode.from(BuildConfig.DEBUG), platformRuntimeConfig().buildMode)
    }
}
