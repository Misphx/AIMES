package com.example.aimesdes.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import kotlinx.coroutines.tasks.await

object OcrEngine {
    private val recognizer by lazy { TextRecognition.getClient() }

    suspend fun readText(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.process(image).await()
        return result.text ?:""
    }
}
