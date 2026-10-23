package org.kazusa.matter.core.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionExpiredBusTest {

    @Test
    fun `有订阅者时失效事件送达`() = runBlocking {
        val bus = SessionExpiredBus()
        var fired = 0
        val collector = launch(Dispatchers.Unconfined) {
            bus.expired.collect { fired++ }
        }
        try {
            bus.notifySessionExpired()
            assertEquals(1, fired)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `无订阅者时上报不抛异常`() {
        SessionExpiredBus().notifySessionExpired()
    }
}
