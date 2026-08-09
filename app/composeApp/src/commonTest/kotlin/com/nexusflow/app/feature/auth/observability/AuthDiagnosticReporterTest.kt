package com.nexusflow.app.feature.auth.observability

import com.nexusflow.app.core.observability.AppLogger
import com.nexusflow.app.core.observability.LogFields
import com.nexusflow.app.core.observability.LogLevel
import com.nexusflow.app.core.observability.LogTag
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthDiagnosticReporterTest {
    @Test
    fun mapsTypedFailureToStableErrorEvent() {
        val logger = RecordingLogger()
        val reporter = AppLoggerAuthDiagnosticReporter(logger)

        reporter.report(AuthDiagnosticEvent.SessionRefresh(RefreshOutcome.UNAUTHENTICATED))

        assertEquals(LogCall.Error, logger.calls.single().kind)
        assertEquals("auth_session_refresh", logger.calls.single().event)
    }

    @Test
    fun mapperFailureIsIsolated() {
        val reporter = AppLoggerAuthDiagnosticReporter(ThrowingLogger)

        reporter.report(AuthDiagnosticEvent.GoogleSignIn(GoogleSignInOutcome.PLATFORM_FAILED))
    }

    private data class RecordedCall(
        val kind: LogCall,
        val event: String,
    )

    private enum class LogCall {
        Debug,
        Info,
        Error,
    }

    private class RecordingLogger : AppLogger {
        val calls = mutableListOf<RecordedCall>()

        override fun log(
            level: LogLevel,
            tag: LogTag,
            event: String,
            fields: LogFields,
            cause: Throwable?,
        ) {
            calls +=
                RecordedCall(
                    kind =
                        when (level) {
                            LogLevel.DEBUG -> LogCall.Debug
                            LogLevel.INFO -> LogCall.Info
                            LogLevel.ERROR -> LogCall.Error
                        },
                    event = event,
                )
        }
    }

    private object ThrowingLogger : AppLogger {
        override fun log(
            level: LogLevel,
            tag: LogTag,
            event: String,
            fields: LogFields,
            cause: Throwable?,
        ) = error("logger failed")
    }
}
