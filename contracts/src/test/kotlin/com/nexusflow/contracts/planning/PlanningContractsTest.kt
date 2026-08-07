package com.nexusflow.contracts.planning

import java.time.Instant
import com.nexusflow.contracts.api.CreateTaskRequest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PlanningContractsTest {
    @Test
    fun `plan item rejects an inverted time interval`() {
        assertFailsWith<IllegalArgumentException> {
            PlanItem(
                itemId = "item-1",
                title = "Liverpool match",
                domain = OpportunityDomain.SPORTS,
                startAt = Instant.parse("2026-08-08T12:00:00Z"),
                endAt = Instant.parse("2026-08-08T11:00:00Z"),
            )
        }
    }

    @Test
    fun `money requires a nonnegative minor amount and ISO currency`() {
        assertFailsWith<IllegalArgumentException> { Money(-1, "CNY") }
        assertFailsWith<IllegalArgumentException> { Money(300, "cny") }
    }

    @Test
    fun `task request requires an IANA timezone`() {
        CreateTaskRequest(requestText = "Plan my weekend", timezone = "Asia/Shanghai")

        assertFailsWith<IllegalArgumentException> {
            CreateTaskRequest(requestText = "Plan my weekend", timezone = "UTC+8")
        }
    }
}
