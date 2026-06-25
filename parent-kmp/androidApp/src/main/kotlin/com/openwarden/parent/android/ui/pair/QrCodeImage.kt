package com.openwarden.parent.android.ui.pair

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders [content] as a scannable QR code (ADR-043 D7). The §7.1 pairing payload JSON is encoded to a
 * ZXing [com.google.zxing.common.BitMatrix] once (remembered on [content]) and drawn as black modules on
 * a white quiet-zone background via a Compose [Canvas] — no Android `Bitmap` needed.
 *
 * Pure presentation: it holds no key material beyond the public §7.1 payload it is handed (the nonce +
 * parent pubkeys are not secret — they are exactly what the QR is for). Error-correction is set to M
 * (15%) so a phone camera reads it reliably off a screen.
 */
@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
    moduleColor: Color = Color.Black,
    backgroundColor: Color = Color.White,
) {
    val matrix =
        remember(content) {
            val hints =
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN to 1,
                )
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, QR_PIXELS, QR_PIXELS, hints)
        }

    Canvas(modifier = modifier) {
        val cells = matrix.width // square
        val cell = size.minDimension / cells
        // Paint the quiet-zone background first so the QR has the white border scanners require.
        drawRect(color = backgroundColor, size = Size(size.minDimension, size.minDimension))
        for (y in 0 until cells) {
            for (x in 0 until cells) {
                if (matrix.get(x, y)) {
                    drawRect(
                        color = moduleColor,
                        topLeft = Offset(x * cell, y * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}

/** Encode resolution handed to ZXing; the [Canvas] scales modules to its actual size. */
private const val QR_PIXELS = 512

/** A sensible default on-screen QR size for the pairing screen. */
val DEFAULT_QR_SIZE = 240.dp
