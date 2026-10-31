package com.vpnproxy.ace.ipc

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedTelemetryQueueTest {

    @Test
    fun rejectsRecordsWhenCapacityIsFullWithoutBlockingProducer() = runBlocking {
        val queue = BoundedTelemetryQueue(capacity = 1, maxRecordBytes = 64)

        assertTrue(queue.offer("first"))
        assertFalse(queue.offer("second"))
        assertEquals("first", queue.receive())
        assertTrue(queue.offer("third"))
        assertEquals("third", queue.receive())
    }

    @Test
    fun rejectsOversizedUtf8Payload() = runBlocking {
        val queue = BoundedTelemetryQueue(capacity = 2, maxRecordBytes = 4)

        assertFalse(queue.offer("12345"))
        assertFalse(queue.offer("€€")) // six UTF-8 bytes, not two characters
        assertTrue(queue.offer("1234"))
        assertEquals("1234", queue.receive())
    }

    @Test
    fun closeDrainsAcceptedRecordsThenReturnsNull() = runBlocking {
        val queue = BoundedTelemetryQueue(capacity = 2, maxRecordBytes = 64)

        assertTrue(queue.offer("accepted"))
        queue.close()

        assertEquals("accepted", queue.receive())
        assertNull(queue.receive())
        assertFalse(queue.offer("after-close"))
    }

    @Test
    fun defaultLimitsMatchBinderContract() {
        assertEquals(128, BoundedTelemetryQueue.DEFAULT_CAPACITY)
        assertEquals(16 * 1024, BoundedTelemetryQueue.DEFAULT_MAX_RECORD_BYTES)
    }
}
