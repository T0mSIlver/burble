package dev.silver.ramblerbubble

import java.io.ByteArrayOutputStream

/** Minimal protobuf wire-format writer/reader for the speech.s3 messages. */
object Pb {

    fun varint(v: Long): ByteArray {
        var x = v
        val out = ByteArrayOutputStream()
        while (true) {
            if (x and 0x7FL.inv() == 0L) {
                out.write(x.toInt())
                return out.toByteArray()
            }
            out.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
    }

    fun tag(field: Int, wireType: Int) = varint((field.toLong() shl 3) or wireType.toLong())

    fun int(field: Int, v: Long) = tag(field, 0) + varint(v)
    fun bool(field: Int, v: Boolean) = int(field, if (v) 1 else 0)
    fun bytes(field: Int, b: ByteArray) = tag(field, 2) + varint(b.size.toLong()) + b
    fun str(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))
    fun msg(field: Int, b: ByteArray) = bytes(field, b)
    fun f32(field: Int, v: Float): ByteArray {
        val bits = java.lang.Float.floatToIntBits(v)
        val b = byteArrayOf(
            (bits and 0xFF).toByte(), (bits shr 8 and 0xFF).toByte(),
            (bits shr 16 and 0xFF).toByte(), (bits shr 24 and 0xFF).toByte(),
        )
        return tag(field, 5) + b
    }

    operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val out = ByteArray(size + other.size)
        System.arraycopy(this, 0, out, 0, size)
        System.arraycopy(other, 0, out, size, other.size)
        return out
    }

    /** A generic decoded field: number, wire type, and raw value. */
    data class Field(val num: Int, val wt: Int, val value: Any?)

    /** Decodes a buffer as generic protobuf; returns null when it is not valid protobuf. */
    fun decode(buf: ByteArray): List<Field>? {
        val out = ArrayList<Field>()
        var i = 0
        try {
            while (i < buf.size) {
                val kr = readVarint(buf, i) ?: return null
                val key = kr.first
                i = kr.second
                val num = (key ushr 3).toInt()
                val wt = (key and 7).toInt()
                if (num == 0) return null
                when (wt) {
                    0 -> {
                        val r = readVarint(buf, i) ?: return null
                        i = r.second
                        out.add(Field(num, wt, r.first))
                    }
                    1 -> {
                        if (i + 8 > buf.size) return null
                        out.add(Field(num, wt, buf.copyOfRange(i, i + 8)))
                        i += 8
                    }
                    2 -> {
                        val l = readVarint(buf, i) ?: return null
                        i = l.second
                        val len = l.first.toInt()
                        if (len < 0 || i + len > buf.size) return null
                        out.add(Field(num, wt, buf.copyOfRange(i, i + len)))
                        i += len
                    }
                    5 -> {
                        if (i + 4 > buf.size) return null
                        out.add(Field(num, wt, buf.copyOfRange(i, i + 4)))
                        i += 4
                    }
                    else -> return null
                }
            }
        } catch (e: Exception) {
            return null
        }
        return if (i == buf.size) out else null
    }

    fun fields(buf: ByteArray, field: Int): List<ByteArray> =
        decode(buf)?.filter { it.num == field && it.wt == 2 }?.map { it.value as ByteArray }
            ?: emptyList()

    fun first(buf: ByteArray, field: Int): ByteArray? = fields(buf, field).firstOrNull()

    fun firstVarint(buf: ByteArray, field: Int): Long? =
        decode(buf)?.firstOrNull { it.num == field && it.wt == 0 }?.value as? Long

    private fun readVarint(buf: ByteArray, start: Int): Pair<Long, Int>? {
        var v = 0L
        var shift = 0
        var i = start
        while (true) {
            if (i >= buf.size || shift > 63) return null
            val b = buf[i].toInt() and 0xFF
            i++
            v = v or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return v to i
            shift += 7
        }
    }
}
