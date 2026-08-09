package com.nexusflow.app.core.time

import org.koin.core.module.Module
import org.koin.dsl.module

/** Installs the platform clock supplied by the platform composition root. */
fun timeModule(appClock: AppClock): Module =
    module {
        single<AppClock> { appClock }
    }
