package wasai.link.mcilm.top.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object QrCodeUtils {

    /**
     * 生成二维码位图。
     *
     * 【修复】原来逐像素 `setPixel`（512×512 = 26 万次像素级调用），
     * 现在先写 IntArray 再一次性 `setPixels`，耗时降到个位数毫秒。
     * 这仍是 CPU 操作，请配合 [generateQrCodeAsync] 在后台线程调用。
     */
    fun generateQrCode(text: String, size: Int = 512): Bitmap? {
        if (text.isEmpty() || size <= 0) return null
        return try {
            val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val w = bitMatrix.width
            val h = bitMatrix.height
            if (w <= 0 || h <= 0) return null

            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val rowOffset = y * w
                for (x in 0 until w) {
                    pixels[rowOffset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 后台线程版本：供 Compose 的 produceState 使用，避免在主线程做编码与位图填充 */
    suspend fun generateQrCodeAsync(text: String, size: Int = 512): Bitmap? =
        withContext(Dispatchers.Default) { generateQrCode(text, size) }
}
