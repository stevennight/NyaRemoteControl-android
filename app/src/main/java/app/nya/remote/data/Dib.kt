package app.nya.remote.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Windows CF_DIB (what the host's clipboard images travel as) to and from
 * ARGB pixels. Reads 24/32-bit BI_RGB and BI_BITFIELDS, bottom-up or
 * top-down; writes 32-bit BI_RGB bottom-up.
 */
object Dib {
    class Pixels(val width: Int, val height: Int, val argb: IntArray)

    fun decode(dib: ByteArray): Pixels? {
        if (dib.size < 40) return null
        val b = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = b.getInt(0)
        val width = b.getInt(4)
        val rawHeight = b.getInt(8)
        val bits = b.getShort(14).toInt()
        val compression = b.getInt(16)
        val colorsUsed = b.getInt(32)
        if (width <= 0 || rawHeight == 0 || width > 16384 || kotlin.math.abs(rawHeight) > 16384) return null
        if (bits != 24 && bits != 32) return null
        if (compression != 0 && compression != 3) return null
        val height = kotlin.math.abs(rawHeight)
        val topDown = rawHeight < 0
        // BI_BITFIELDS with a 40-byte header: three masks follow it.
        var offset = headerSize + if (compression == 3 && headerSize == 40) 12 else 0
        offset += colorsUsed * 4
        val stride = ((width * bits + 31) / 32) * 4
        if (dib.size < offset + stride * height) return null
        val argb = IntArray(width * height)
        // Many 32-bit DIBs leave alpha 0: then treat the image as opaque.
        var anyAlpha = false
        if (bits == 32) {
            loop@ for (y in 0 until height) for (x in 0 until width) {
                if (dib[offset + y * stride + x * 4 + 3].toInt() != 0) {
                    anyAlpha = true
                    break@loop
                }
            }
        }
        for (y in 0 until height) {
            val row = offset + (if (topDown) y else height - 1 - y) * stride
            for (x in 0 until width) {
                val p = row + x * (bits / 8)
                val blue = dib[p].toInt() and 0xff
                val green = dib[p + 1].toInt() and 0xff
                val red = dib[p + 2].toInt() and 0xff
                val a = if (bits == 32 && anyAlpha) dib[p + 3].toInt() and 0xff else 0xff
                argb[y * width + x] = (a shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return Pixels(width, height, argb)
    }

    fun encode(p: Pixels): ByteArray {
        val stride = p.width * 4
        val b = ByteBuffer.allocate(40 + stride * p.height).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(40).putInt(p.width).putInt(p.height).putShort(1).putShort(32)
        b.putInt(0).putInt(stride * p.height).putInt(2835).putInt(2835).putInt(0).putInt(0)
        for (y in p.height - 1 downTo 0) {
            for (x in 0 until p.width) {
                val c = p.argb[y * p.width + x]
                b.put((c and 0xff).toByte()).put((c shr 8 and 0xff).toByte()).put((c shr 16 and 0xff).toByte()).put((c ushr 24).toByte())
            }
        }
        return b.array()
    }
}
