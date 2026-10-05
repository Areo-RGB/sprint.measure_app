package com.sprinttiming.app

import java.nio.ByteBuffer

/**
 * Reusable upright luma grid. [fill] copies a Camera2 Y plane into screen orientation so detector
 * columns match the preview: the sensor image is rotated clockwise by `rotationDegrees`
 * (SENSOR_ORIENTATION for the portrait-locked UI) and, for the front camera, mirrored like the
 * TextureView preview. With `step` > 1 each output pixel averages a step×step block (2×2 samples).
 */
class LumaFrame {
    var width = 0; private set
    var height = 0; private set
    var data = ByteArray(0); private set

    fun fill(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, srcWidth: Int, srcHeight: Int, rotationDegrees: Int, mirror: Boolean, step: Int = 1): LumaFrame {
        val rotation = SensorGeometry.normalize(rotationDegrees)
        val sideways = rotation == 90 || rotation == 270
        val uprightW = if (sideways) srcHeight else srcWidth
        val uprightH = if (sideways) srcWidth else srcHeight
        val s = step.coerceAtLeast(1)
        val outW = uprightW / s
        val outH = uprightH / s
        if (data.size != outW * outH) data = ByteArray(outW * outH)
        width = outW; height = outH
        val half = s / 2
        // Inverse of the clockwise rotation (after undoing the mirror): upright (x, y) -> buffer index.
        fun index(ux: Int, uy: Int): Int {
            val x = if (mirror) uprightW - 1 - ux else ux
            return when (rotation) {
                90 -> (uprightW - 1 - x) * rowStride + uy * pixelStride
                180 -> (srcHeight - 1 - uy) * rowStride + (srcWidth - 1 - x) * pixelStride
                270 -> x * rowStride + (srcWidth - 1 - uy) * pixelStride
                else -> uy * rowStride + x * pixelStride
            }
        }
        for (gy in 0 until outH) {
            val y = gy * s
            val rowOut = gy * outW
            for (gx in 0 until outW) {
                val x = gx * s
                data[rowOut + gx] = if (half == 0) buffer.get(index(x, y)) else {
                    val sum = (buffer.get(index(x, y)).toInt() and 255) + (buffer.get(index(x + half, y)).toInt() and 255) +
                        (buffer.get(index(x, y + half)).toInt() and 255) + (buffer.get(index(x + half, y + half)).toInt() and 255)
                    (sum shr 2).toByte()
                }
            }
        }
        return this
    }
}

/** Geometry shared by the CPU (ImageReader) and GPU (high-speed) analysis paths. */
object SensorGeometry {
    fun normalize(degrees: Int) = ((degrees % 360) + 360) % 360

    /**
     * Rolling-shutter readout fraction of the sensor row that an upright screen column maps to:
     * `fraction = a + b * xFraction`. Rows are read top to bottom in sensor coordinates, so a
     * vertical timing line in the portrait UI is one sensor row for 90°/270° sensors. For 0°/180°
     * the line spans every row and the ROI centre (0.5) is used.
     */
    fun readoutCoefficients(rotationDegrees: Int, mirror: Boolean): Pair<Float, Float> = when (normalize(rotationDegrees)) {
        90 -> if (mirror) 0f to 1f else 1f to -1f
        270 -> if (mirror) 1f to -1f else 0f to 1f
        else -> 0.5f to 0f
    }

    /** Upright width / height for a sensor buffer of the given size. */
    fun uprightAspect(width: Int, height: Int, rotationDegrees: Int): Float {
        val r = normalize(rotationDegrees)
        return if (r == 90 || r == 270) height.toFloat() / width else width.toFloat() / height
    }
}
