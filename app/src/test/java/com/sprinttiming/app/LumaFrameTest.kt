package com.sprinttiming.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class LumaFrameTest {
    // 3×2 source:  1 2 3
    //              4 5 6
    private val source = byteArrayOf(1, 2, 3, 4, 5, 6)

    private fun copy(rotation: Int, mirror: Boolean = false) = LumaFrame().fill(ByteBuffer.wrap(source), 3, 1, 3, 2, rotation, mirror)

    @Test fun keepsOrientationAtZeroDegrees() {
        val frame = copy(0)
        assertEquals(3, frame.width); assertEquals(2, frame.height)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), frame.data)
    }

    @Test fun rotatesClockwiseBy90() {
        val frame = copy(90)
        assertEquals(2, frame.width); assertEquals(3, frame.height)
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), frame.data)
    }

    @Test fun rotatesBy180() = assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), copy(180).data)

    @Test fun rotatesClockwiseBy270() {
        val frame = copy(270)
        assertEquals(2, frame.width); assertEquals(3, frame.height)
        assertArrayEquals(byteArrayOf(3, 6, 2, 5, 1, 4), frame.data)
    }

    @Test fun mirrorsFrontCameraAfterRotation() = assertArrayEquals(byteArrayOf(6, 3, 5, 2, 4, 1), copy(270, mirror = true).data)

    @Test fun honoursRowAndPixelStride() {
        // Row stride 8 and pixel stride 2 with padding bytes (99) that must be skipped.
        val padded = byteArrayOf(1, 99, 2, 99, 3, 99, 99, 99, 4, 99, 5, 99, 6, 99, 99, 99)
        val frame = LumaFrame().fill(ByteBuffer.wrap(padded), 8, 2, 3, 2, 90, false)
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), frame.data)
    }

    @Test fun downsamplesByAveragingBlocks() {
        // 4×2 source, step 2, no rotation: two 2×2 blocks averaged.
        val src = byteArrayOf(10, 20, 100, 100, 30, 40, 100, 100)
        val frame = LumaFrame().fill(ByteBuffer.wrap(src), 4, 1, 4, 2, 0, false, step = 2)
        assertEquals(2, frame.width); assertEquals(1, frame.height)
        assertArrayEquals(byteArrayOf(25, 100), frame.data)
    }

    @Test fun readoutFollowsSensorRows() {
        assertEquals(1f to -1f, SensorGeometry.readoutCoefficients(90, false))
        assertEquals(0f to 1f, SensorGeometry.readoutCoefficients(90, true))
        assertEquals(0f to 1f, SensorGeometry.readoutCoefficients(270, false))
        assertEquals(1f to -1f, SensorGeometry.readoutCoefficients(270, true))
        assertEquals(0.5f to 0f, SensorGeometry.readoutCoefficients(0, false))
    }
}
