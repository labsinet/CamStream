package com.example.camstream

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Зберігає останній JPEG-кадр і будить клієнтів, коли з'являється новий. */
object FrameHub {
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private var last: ByteArray? = null
    private var seq = 0L

    @Volatile var stopped = false

    fun push(jpeg: ByteArray) = lock.withLock {
        last = jpeg
        seq++
        cond.signalAll()
    }

    fun latest(): ByteArray? = lock.withLock { last }

    /** Чекає кадр новіший за [after]. Повертає (номер, дані) або null за таймаутом. */
    fun await(after: Long, timeoutMs: Long): Pair<Long, ByteArray>? = lock.withLock {
        if (seq == after && !stopped) cond.await(timeoutMs, TimeUnit.MILLISECONDS)
        val l = last
        if (l != null && seq != after) seq to l else null
    }

    fun reset() = lock.withLock { last = null; seq = 0; stopped = false }

    fun stop() = lock.withLock { stopped = true; cond.signalAll() }
}
