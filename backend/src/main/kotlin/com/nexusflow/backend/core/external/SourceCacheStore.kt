package com.nexusflow.backend.core.external

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

interface SourceCacheStore {
    suspend fun get(key: SourceCacheKey): ByteArray?

    suspend fun put(
        key: SourceCacheKey,
        value: ByteArray,
        ttl: Duration,
    )

    suspend fun remove(key: SourceCacheKey)
}

@JvmInline
value class SourceCacheKey(val value: String) {
    init {
        require(value.startsWith("source:v")) { "Source cache key must be versioned" }
    }
}

interface SourceCacheCodec<T> {
    fun encode(value: T): ByteArray

    fun decode(bytes: ByteArray): T
}

enum class SourceCacheResultKind {
    Success,
    SuccessEmpty,
}

data class SourceCacheTtlPolicy(
    val successTtl: Duration,
    val emptyTtl: Duration,
) {
    init {
        require(!successTtl.isNegative && !successTtl.isZero) { "successTtl must be positive" }
        require(!emptyTtl.isNegative && !emptyTtl.isZero) { "emptyTtl must be positive" }
    }

    fun ttlFor(kind: SourceCacheResultKind): Duration =
        when (kind) {
            SourceCacheResultKind.Success -> successTtl
            SourceCacheResultKind.SuccessEmpty -> emptyTtl
        }
}

class InMemorySourceCacheStore(
    private val clock: Clock = Clock.systemUTC(),
) : SourceCacheStore {
    private val entries = ConcurrentHashMap<SourceCacheKey, CacheEntry>()

    override suspend fun get(key: SourceCacheKey): ByteArray? {
        val entry = entries[key] ?: return null
        if (!entry.expiresAt.isAfter(clock.instant())) {
            entries.remove(key, entry)
            return null
        }
        return entry.value.copyOf()
    }

    override suspend fun put(
        key: SourceCacheKey,
        value: ByteArray,
        ttl: Duration,
    ) {
        if (!ttl.isPositive()) {
            entries.remove(key)
            return
        }
        entries[key] = CacheEntry(value.copyOf(), clock.instant().plus(ttl))
    }

    override suspend fun remove(key: SourceCacheKey) {
        entries.remove(key)
    }

    private data class CacheEntry(
        val value: ByteArray,
        val expiresAt: Instant,
    )
}

private fun Duration.isPositive(): Boolean = !isNegative && !isZero
