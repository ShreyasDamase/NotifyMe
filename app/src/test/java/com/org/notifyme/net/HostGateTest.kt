package com.org.notifyme.net

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostGateTest {

    @Test
    fun testMinGap() {
        val time = 1000L
        val gate = HostGate { time }
        val host = "robu.in"
        
        val firstAllowed = gate.allowedAt(host)
        assertTrue(firstAllowed <= time)
        
        gate.recordRequest(host)
        val nextAllowed = gate.allowedAt(host)
        assertTrue(nextAllowed > time)
    }

    @Test
    fun testPlanEffectiveInterval() {
        assertEquals(180, Plan.effectiveIntervalSec(1, 180))
        assertEquals(360, Plan.effectiveIntervalSec(6, 180))
        assertEquals(120, Plan.effectiveIntervalSec(1, 30))
    }
}
