package com.nexusflow.backend.core.external

import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class InMemorySourceCacheStoreTest {
    @Test
    fun `cache returns copied value until ttl expires`() {
        runBlocking {
            val clock = MutableClock(Instant.parse("2026-09-06T00:00:00Z"))
            val store = InMemorySourceCacheStore(clock)
            val key = SourceCacheKey("source:v1:tavily:web-search:test")
            val value = byteArrayOf(1, 2, 3)

            store.put(key, value, Duration.ofMinutes(5))
            value[0] = 9

            assertContentEquals(byteArrayOf(1, 2, 3), store.get(key))

            clock.now = clock.now.plus(Duration.ofMinutes(5))

            assertNull(store.get(key))
        }
    }

    private class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun instant(): Instant = now

        override fun withZone(zone: ZoneId): Clock = this

        override fun getZone(): ZoneId = ZoneId.of("UTC")
    }
}
