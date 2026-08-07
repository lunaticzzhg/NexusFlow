package com.nexusflow.app

import androidx.compose.runtime.Composable
import com.nexusflow.app.app.AppShell
import com.nexusflow.app.core.design.AppTheme

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
fun AppRoot() {
    AppTheme {
        AppShell()
    }
}
