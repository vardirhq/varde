package no.vardir.varde

import org.junit.Assert.*
import org.junit.Test

class LandscapeTest {
    @Test fun sameSeedProducesSameScene() {
        assertArrayEquals(landscapePixels(42, true), landscapePixels(42, true))
    }
    @Test fun seedAndTimeChangeScene() {
        assertFalse(landscapePixels(42, true).contentEquals(landscapePixels(43, true)))
        assertFalse(landscapePixels(42, true).contentEquals(landscapePixels(42, false)))
    }
}
