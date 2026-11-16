package io.github.sumankayal247.gbaemulator

import io.github.sumankayal247.gbaemulator.domain.model.EmulatorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorStateMachineTest {

    @Test
    fun legalTransitionsAreAccepted() {
        assertTrue(EmulatorState.UNLOADED.canTransitionTo(EmulatorState.LOADING))
        assertTrue(EmulatorState.LOADING.canTransitionTo(EmulatorState.RUNNING))
        assertTrue(EmulatorState.LOADING.canTransitionTo(EmulatorState.PAUSED))
        assertTrue(EmulatorState.RUNNING.canTransitionTo(EmulatorState.PAUSED))
        assertTrue(EmulatorState.PAUSED.canTransitionTo(EmulatorState.RUNNING))
        assertTrue(EmulatorState.RUNNING.canTransitionTo(EmulatorState.STOPPING))
        assertTrue(EmulatorState.PAUSED.canTransitionTo(EmulatorState.STOPPING))
        assertTrue(EmulatorState.STOPPING.canTransitionTo(EmulatorState.STOPPED))
        assertTrue(EmulatorState.STOPPED.canTransitionTo(EmulatorState.UNLOADED))
        assertTrue(EmulatorState.ERROR.canTransitionTo(EmulatorState.UNLOADED))
    }

    @Test
    fun illegalTransitionsAreRejected() {
        assertFalse(EmulatorState.UNLOADED.canTransitionTo(EmulatorState.RUNNING))
        assertFalse(EmulatorState.UNLOADED.canTransitionTo(EmulatorState.PAUSED))
        assertFalse(EmulatorState.STOPPING.canTransitionTo(EmulatorState.RUNNING))
        assertFalse(EmulatorState.STOPPING.canTransitionTo(EmulatorState.PAUSED))
        assertFalse(EmulatorState.STOPPED.canTransitionTo(EmulatorState.RUNNING))
        assertFalse(EmulatorState.ERROR.canTransitionTo(EmulatorState.RUNNING))
    }

    @Test
    fun nativeMappingRoundTripsAllStates() {
        for (state in EmulatorState.entries) {
            assertEquals(state, EmulatorState.fromNative(state.nativeValue))
        }
        assertEquals(EmulatorState.ERROR, EmulatorState.fromNative(999))
    }
}
