package com.nexusflow.app.core.security

import org.koin.core.module.Module
import org.koin.dsl.module

/** Installs the platform secure store supplied by the platform composition root. */
fun secureStoreModule(secureStore: SecureStore): Module =
    module {
        single<SecureStore> { secureStore }
    }
