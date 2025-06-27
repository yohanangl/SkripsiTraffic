package com.example.home_traffic

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.home_traffic.data.Detection
import com.example.home_traffic.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DetectionActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var controlButtonContainer: LinearLayout
    private lateinit var btnZoomNormal: Button
    private lateinit var btnZoom2x: Button
    private lateinit var btnSelesaiDeteksi: Button

    private val REQUEST_CAMERA_PERMISSION = 10
    private var tts: TextToSpeech? = null
    private val spokenSigns = mutableSetOf<String>()
    private var cameraControl: CameraControl? = null
    private val isProcessingImage = AtomicBoolean(false)
    private val IMAGE_PROCESSING_COOLDOWN_MILLIS = 1000L
    private var lastImageSentTime: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detection)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        controlButtonContainer = findViewById(R.id.controlButtonContainer)
        btnZoomNormal = findViewById(R.id.btnZoomNormal)
        btnZoom2x = findViewById(R.id.btnZoom2x)
        btnSelesaiDeteksi = findViewById(R.id.btnSelesaiDeteksi)

        btnSelesaiDeteksi.setOnClickListener {
            finish()
        }

        controlButtonContainer.visibility = View.GONE

        tts = TextToSpeech(this, this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts!!.setLanguage(Locale("id", "ID"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts!!.setLanguage(Locale.US) // Fallback ke Bahasa Inggris
            }
            tts!!.setSpeechRate(0.9f)
            tts!!.setPitch(1.0f)
        } else {
            Log.e("TTS", "Inisialisasi TextToSpeech gagal!")
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_BLOCK_PRODUCER)
                .build()
                .also {
                    it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        val currentTime = System.currentTimeMillis()
                        if (!isProcessingImage.get() && (currentTime - lastImageSentTime > IMAGE_PROCESSING_COOLDOWN_MILLIS)) {
                            isProcessingImage.set(true)
                            lastImageSentTime = currentTime
                            sendImageToApi(imageProxy) {
                                isProcessingImage.set(false)
                            }
                        } else {
                            imageProxy.close()
                        }
                    }
                }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
                cameraControl = camera.cameraControl
                cameraControl?.setZoomRatio(4.0f)

                val cameraInfo = camera.cameraInfo

                // ===================================================
                // PERBAIKAN ADA DI BARIS DI BAWAH INI
                // ===================================================
                cameraInfo.zoomState.observe(this) { state ->
                    controlButtonContainer.visibility = View.VISIBLE
                    val minZoom = state.minZoomRatio
                    val maxZoom = state.maxZoomRatio
                    btnZoomNormal.setOnClickListener {
                        cameraControl?.setZoomRatio(1.0f.coerceIn(minZoom, maxZoom))
                    }
                    btnZoom2x.setOnClickListener {
                        cameraControl?.setZoomRatio(2.0f.coerceIn(minZoom, maxZoom))
                    }
                }

            } catch (e: Exception) {
                Log.e("DetectionActivity", "Kamera gagal dimulai: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun sendImageToApi(imageProxy: ImageProxy, onComplete: () -> Unit) {
        val originalBitmap = imageProxy.toBitmap()
        val rotatedBitmap = rotateBitmap(originalBitmap, imageProxy.imageInfo.rotationDegrees)
        val byteArrayOutputStream = ByteArrayOutputStream()
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream)
        val imageBytes = byteArrayOutputStream.toByteArray()
        imageProxy.close()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val requestBody = imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull(), 0, imageBytes.size)
                val multipartBody = MultipartBody.Part.createFormData("file", "image.jpg", requestBody)
                val response = RetrofitClient.apiService.uploadImage(multipartBody)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        val predictionResponse = response.body()
                        if (predictionResponse != null && predictionResponse.detections.isNotEmpty()) {
                            overlayView.setResults(
                                predictionResponse.detections,
                                rotatedBitmap.width,
                                rotatedBitmap.height
                            )
                            val topDetection = predictionResponse.detections.maxByOrNull { it.confidence }
                            topDetection?.let { detection ->
                                val label = detection.class_name
                                if (!spokenSigns.contains(label)) {
                                    spokenSigns.add(label)
                                    val infoText = getInfoTextForLabel(label)
                                    speakOut("Rambu $label. $infoText")
                                }
                            }
                        } else {
                            overlayView.clear()
                            spokenSigns.clear()
                        }
                    } else {
                        overlayView.clear()
                        spokenSigns.clear()
                        Toast.makeText(this@DetectionActivity, "API Error: ${response.code()}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    overlayView.clear()
                    spokenSigns.clear()
                    Toast.makeText(this@DetectionActivity, "Error koneksi: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                onComplete()
            }
        }
    }
    private fun getInfoTextForLabel(label: String): String {
        return when (label) {
            "AreaPutarBalik" -> "Area untuk putar balik."
            "dilarangBelokKanan" -> "Dilarang belok kanan."
            "dilarangBelokKiri" -> "Jangan belok ke kiri di area ini."
            "dilarangBerhenti" -> "Dilarang berhenti di sepanjang jalan ini."
            "dilarangParkir" -> "Jangan parkir kendaraan di area ini."
            "dilarangPutarBalik" -> "Dilarang melakukan putar balik di area ini."
            "hatihati" -> "Harap mengemudi dengan hati-hati."
            "isyaratLaluLintas" -> "Perhatikan lampu atau isyarat lalu lintas di depan."
            "jalurKeretaApi" -> "Kurangi kecepatan, akan melewati jalur kereta api."
            "jalurSepeda" -> "Waspadai jalur khusus untuk sepeda di sekitar Anda."
            "laranganLebih30Km" -> "Jaga kecepatan di bawah 30 kilometer per jam."
            "laranganMenyalip" -> "Jangan menyalip kendaraan lain di jalur ini."
            "pejalanKakiAnakAnak" -> "Kurangi kecepatan, banyak anak-anak di sekitar."
            "penyempitanJalan-jembatan" -> "Kurangi kecepatan, jalan menyempit di depan."
            "peringatan-pejalanKakiZebraCross" -> "Waspadai pejalan kaki yang menyeberang di zebra cross."
            "perintahPejalanKaki" -> "Beri jalan untuk pejalan kaki."
            "persimpanganEmpat" -> "Akan memasuki persimpangan empat, waspadai kendaraan dari semua arah."
            "persimpanganEmpatPrioritas" -> "Prioritaskan kendaraan dari jalan utama di persimpangan empat."
            "persimpanganTigaKanan" -> "Persiapkan untuk belok ke kanan di persimpangan tiga."
            "persimpanganTigaKiri" -> "Persiapkan untuk belok ke kiri di persimpangan tiga."
            "simpangTigaKananPrioritas" -> "Berikan prioritas pada jalan utama di simpang tiga kanan."
            "simpangTigaKiriPrioritas" -> "Berikan prioritas pada jalan utama di simpang tiga kiri."
            else -> "Informasi tidak tersedia."
        }
    }

    private fun speakOut(text: String) {
        if (tts != null && tts!!.isSpeaking) {
            tts!!.stop()
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Izin kamera ditolak. Aplikasi tidak dapat berfungsi.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        if (tts != null) {
            tts!!.stop()
            tts!!.shutdown()
        }
        super.onDestroy()
    }
}