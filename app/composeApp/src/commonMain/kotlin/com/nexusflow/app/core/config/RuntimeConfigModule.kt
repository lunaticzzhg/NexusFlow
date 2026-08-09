package com.nexusflow.app.core.config

import org.koin.core.module.Module
import org.koin.dsl.module

/** Installs the immutable runtime facts supplied by the platform composition root. */
fun runtimeConfigModule(runtimeConfig: RuntimeConfig): Module =
    module {
        single { runtimeConfig }
    }
