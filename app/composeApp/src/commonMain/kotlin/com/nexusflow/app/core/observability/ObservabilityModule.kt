package com.nexusflow.app.core.observability

import org.koin.dsl.module

val observabilityModule =
    module {
        single { createPlatformLogSink() }
        single<AppLogger> { StructuredAppLogger(runtimeConfig = get(), sink = get()) }
    }
