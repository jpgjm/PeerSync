package com.example.peersync.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CallGate (0.2.37): free only after every call that entered before close has returned. */
class CallGateTest {
    @Test
    fun runsWhileOpenAndRefusesAfterClose() {
        val g = CallGate()
        assertEquals(5, g.enter { 5 })
        assertTrue(g.close())
        assertFalse(g.close()) // second close: caller must not free twice
        assertNull(g.enter { error("must not run after close") })
        assertEquals(0, g.activeCalls)
    }

    @Test
    fun awaitIdleWaitsForCallsStartedBeforeClose() = runBlocking {
        val g = CallGate()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val freedWhileBusy = AtomicBoolean(false)
        // a "blocking FFI call" on another thread
        val call = async(Dispatchers.IO) {
            g.enter {
                inside.countDown()
                release.await()
                1
            }
        }
        inside.await()
        assertTrue(g.close())
        val freer = launch(Dispatchers.Default) {
            g.awaitIdle()
            if (g.activeCalls > 0) freedWhileBusy.set(true)
        }
        delay(100)
        assertFalse(freer.isCompleted) // still waiting for the call
        release.countDown() // "ps_node_close made the blocked call return"
        assertEquals(1, call.await())
        freer.join()
        assertFalse(freedWhileBusy.get())
    }

    @Test
    fun exceptionsLeaveTheCountBalanced() {
        val g = CallGate()
        runCatching { g.enter<Int> { throw IllegalStateException("ffi error") } }
        assertEquals(0, g.activeCalls)
    }
}
