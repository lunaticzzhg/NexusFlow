package com.nexusflow.app.app.startup

import org.koin.dsl.module

val appStartupModule =
    module {
        single { AppStartup(authSessionController = get()) }
    }
