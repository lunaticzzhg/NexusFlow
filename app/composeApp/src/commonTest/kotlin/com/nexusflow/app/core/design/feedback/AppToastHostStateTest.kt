package com.nexusflow.app.core.design.feedback

import kotlin.test.Test
import kotlin.test.assertEquals

class AppToastHostStateTest {
    @Test
    fun showReplacesTheCurrentToast() {
        val state = AppToastHostState()
        val firstToast = AppToast.Success("First")
        val secondToast = AppToast.Error("Second")

        state.show(firstToast)
        state.show(secondToast)

        assertEquals(secondToast, state.currentToast)
    }

    @Test
    fun dismissingAnOldToastDoesNotDismissTheCurrentToast() {
        val state = AppToastHostState()
        val firstToast = AppToast.Success("First")
        val secondToast = AppToast.Error("Second")

        state.show(firstToast)
        val firstToastId = state.currentToastId()!!
        state.show(secondToast)

        state.dismiss(firstToastId)

        assertEquals(secondToast, state.currentToast)
    }
}
