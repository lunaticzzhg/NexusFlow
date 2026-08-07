package com.nexusflow.app

import androidx.compose.ui.window.ComposeUIViewController
import com.nexusflow.app.app.initKoin
import com.nexusflow.app.core.config.platformRuntimeConfig
import platform.UIKit.UIViewController

fun mainViewController(): UIViewController {
    initKoin(platformRuntimeConfig())
    return ComposeUIViewController { AppRoot() }
}
