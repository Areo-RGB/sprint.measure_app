package com.sprinttiming.app

import java.nio.ByteBuffer

/**
 * Copies a Camera2 Y plane into screen orientation so detector columns match the preview.
 * The sensor image is rotated clockwise by [rotationDegrees] (SENSOR_ORIENTATION for the
 * portrait-locked UI) and, for the front camera, mirrored like the TextureView preview.
 */
class LumaFrame(val width: Int, val height: Int, val data: ByteArray) {
    companion object {
        fun copy(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, srcWidth: Int, srcHeight: Int, rotationDegrees: Int, mirror: Boolean): LumaFrame {
            val rotation = ((rotationDegrees % 360) + 360) % 360
            val sideways = rotation == 90 || rotation == 270
            val outW = if (sideways) srcHeight else srcWidth
            val outH = if (sideways) srcWidth else srcHeight
            // Destination x/y as x0 + row*xr + col*xc, y0 + row*yr + col*yc.
            var x0: Int; var xr: Int; var xc: Int; val y0: Int; val yr: Int; val yc: Int
            when (rotation) {
                90 -> { x0 = srcHeight - 1; xr = -1; xc = 0; y0 = 0; yr = 0; yc = 1 }
                180 -> { x0 = srcWidth - 1; xr = 0; xc = -1; y0 = srcHeight - 1; yr = -1; yc = 0 }
                270 -> { x0 = 0; xr = 1; xc = 0; y0 = srcWidth - 1; yr = 0; yc = -1 }
                else -> { x0 = 0; xr = 0; xc = 1; y0 = 0; yr = 1; yc = 0 }
            }
            if (mirror) { x0 = outW - 1 - x0; xr = -xr; xc = -xc }
            val out = ByteArray(srcWidth * srcHeight)
            val rowStep = yr * outW + xr
            val colStep = yc * outW + xc
            var rowStart = y0 * outW + x0
            for (row in 0 until srcHeight) {
                val base = row * rowStride
                var index = rowStart
                for (col in 0 until srcWidth) { out[index] = buffer.get(base + col * pixelStride); index += colStep }
                rowStart += rowStep
            }
            return LumaFrame(outW, outH, out)
        }
    }
}
