package moe.RinShiona.Shamrock.xposed.helper

import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicLong

/**
 * Scrubs QQ NT 9.2.x sign "extra" buffers so the host process never observes
 * its own anti-tamper detection bits.
 *
 * Empirical layout of the 14-byte extra (libfekit.so on QQ 9.2.90):
 *
 *   off  0  1  2  3  4  5  6  7  8  9 10 11 12 13
 *  norm 01 31 00 00 00 00 00 00 00 2f 02 00 00 00
 *  det  01 31 10 82 02 00 00 00 00 6f 00 00 00 00
 *                ^^ ^^ ^^             ^^ ^^
 *                detection flag bits   secondary flag + length nibble
 *
 *  - Offsets [2..4] always zero on a clean client; QQ packs hook/probe
 *    detection markers here when libfekit / QSec see Xposed-like state.
 *  - Offset [9] has 0x40 toggled when detection fires (0x2f -> 0x6f).
 *  - Offset [10] flips from 0x02 (clean marker) to 0x00 (detected).
 *  - QQ 9.2.90 packs 0x04 0x02 when libfekit sees Xposed/LSPosed/module
 *    hooks (friend-confirmed: "0402" = Shamrock-like module hook detect).
 *    Typical positions: [11..12] in the 14-byte header, or [14..15] after it.
 *    Example dirty: …00 04 00 04 02 00 — clean target keeps byte[10]=0x02 only.
 *
 *  The sign / token / proto-extra (length-delimited fields 2/3) that follow
 *  byte 14 are signature-relevant; we leave them untouched so the server
 *  still accepts the request.
 */
internal object SignExtraSanitizer {

    private const val HEADER_LEN = 14
    private const val H0: Byte = 0x01
    private const val H1: Byte = 0x31
    private const val DET_BIT_BYTE_9 = 0x40.toByte()
    private const val CLEAN_BYTE_10: Byte = 0x02
    private const val TAIL_FLAG_A: Byte = 0x04
    private const val TAIL_FLAG_B: Byte = 0x02

    @Volatile private var extraField: Field? = null
    @Volatile private var resultClassName: String? = null

    private val sanitizeCount = AtomicLong(0)
    private val detectionHits  = AtomicLong(0)

    fun sanitizeBytes(input: ByteArray?): ByteArray? {
        if (input == null || input.size < HEADER_LEN) return input
        if (input[0] != H0 || input[1] != H1) return input

        val flagsDirty = input[2] != 0.toByte() ||
                         input[3] != 0.toByte() ||
                         input[4] != 0.toByte() ||
                         (input[9].toInt() and DET_BIT_BYTE_9.toInt()) != 0 ||
                         input[10] != CLEAN_BYTE_10 ||
                         hasTail0402(input)

        if (!flagsDirty) return input

        val out = input.copyOf()
        out[2] = 0
        out[3] = 0
        out[4] = 0
        out[9] = (out[9].toInt() and DET_BIT_BYTE_9.toInt().inv()).toByte()
        if (out[10] != CLEAN_BYTE_10) out[10] = CLEAN_BYTE_10
        scrubTail0402(out)
        scrubSliding0402InHeader(out)

        sanitizeCount.incrementAndGet()
        detectionHits.incrementAndGet()
        return out
    }

    /** QQ 9.2.90 module/hook trailer `04 02` anywhere in the first 32 bytes. */
    private fun hasTail0402(input: ByteArray): Boolean {
        val end = minOf(input.size - 1, 32)
        for (i in 0 until end) {
            if (input[i] == TAIL_FLAG_A && input[i + 1] == TAIL_FLAG_B) return true
        }
        return false
    }

    /** Zero any 04 02 pair in the fixed 14-byte detection header (catches layout drift). */
    private fun scrubSliding0402InHeader(out: ByteArray) {
        val end = minOf(out.size - 1, 32)
        var i = 0
        while (i < end) {
            if (out[i] == TAIL_FLAG_A && out[i + 1] == TAIL_FLAG_B) {
                out[i] = 0
                out[i + 1] = 0
            }
            i++
        }
    }

    private fun scrubTail0402(out: ByteArray) {
        if (out.size >= HEADER_LEN + 2 &&
            out[12] == TAIL_FLAG_A &&
            out[14] == TAIL_FLAG_A &&
            out[15] == TAIL_FLAG_B
        ) {
            out[12] = 0
            out[14] = 0
            out[15] = 0
        }
        if (out.size >= HEADER_LEN &&
            out[11] == TAIL_FLAG_A && out[12] == TAIL_FLAG_B
        ) {
            out[11] = 0
            out[12] = 0
        }
        if (out.size >= HEADER_LEN + 2 &&
            out[14] == TAIL_FLAG_A && out[15] == TAIL_FLAG_B
        ) {
            out[14] = 0
            out[15] = 0
        }
        if (out.size >= HEADER_LEN &&
            out[12] == TAIL_FLAG_A && out[13] == TAIL_FLAG_B
        ) {
            out[12] = 0
            out[13] = 0
        }
    }

    fun sanitizeSignResult(result: Any?): Any? {
        if (result == null) return null
        val field = resolveExtraField(result.javaClass) ?: return result
        try {
            val current = field.get(result) as? ByteArray ?: return result
            val cleaned = sanitizeBytes(current) ?: return result
            if (cleaned !== current) {
                field.set(result, cleaned)
            }
        } catch (_: Throwable) {
            // do not break QQ if reflection fails — fall through silently
        }
        return result
    }

    private fun resolveExtraField(cls: Class<*>): Field? {
        extraField?.let { return it }
        // Public byte[] field named "extra".
        try {
            val f = cls.getField("extra")
            if (f.type == ByteArray::class.java) {
                f.isAccessible = true
                extraField = f
                resultClassName = cls.name
                XposedBridge.log("[SignExtraSanitizer] bound extra field on ${cls.name}")
                return f
            }
        } catch (_: NoSuchFieldException) {
            // fall through to declared-field scan
        } catch (_: Throwable) {
            return null
        }
        // Fallback: scan declared fields for a byte[] whose name contains "extra".
        return try {
            cls.declaredFields.firstOrNull {
                it.type == ByteArray::class.java &&
                    it.name.lowercase().contains("extra")
            }?.also {
                it.isAccessible = true
                extraField = it
                resultClassName = cls.name
                XposedBridge.log("[SignExtraSanitizer] bound extra-like field ${it.name} on ${cls.name}")
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun stats(): String =
        "SignExtraSanitizer{cls=${resultClassName ?: "?"}, scrubbed=${sanitizeCount.get()}, hits=${detectionHits.get()}}"
}
