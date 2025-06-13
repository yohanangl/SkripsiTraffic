package com.example.home_traffic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.Engine
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
import java.util.concurrent.atomic.AtomicBoolean // Import ini

class DetectionActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var previewView: PreviewView
    private lateinit var tvLabel: TextView
    private lateinit var tvOutput: TextView
    private lateinit var outputBox: View
    private lateinit var imageViewResult: ImageView

    private lateinit var zoomButtonContainer: LinearLayout
    private lateinit var btnZoom1x: Button
    private lateinit var btnZoom2x: Button
    private lateinit var btnZoom4x: Button

    private val REQUEST_CAMERA_PERMISSION = 10

    private var tts: TextToSpeech? = null

    private val spokenSigns = mutableSetOf<String>()

    private var cameraControl: CameraControl? = null

    // --- Variabel baru untuk mengontrol frekuensi pengiriman gambar ---
    private val isProcessingImage = AtomicBoolean(false)
    private val IMAGE_PROCESSING_COOLDOWN_MILLIS = 1000L // Kirim gambar setiap 1 detik
    private var lastImageSentTime: Long = 0L
    // --- Akhir variabel baru ---


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detection)

        previewView = findViewById(R.id.previewView)
        tvLabel = findViewById(R.id.tvLabel)
        tvOutput = findViewById(R.id.tvOutput)
        outputBox = findViewById(R.id.outputBox)
        imageViewResult = findViewById(R.id.imageViewResult)

        zoomButtonContainer = findViewById(R.id.zoomButtonContainer)
        btnZoom1x = findViewById(R.id.btnZoom1x)
        btnZoom2x = findViewById(R.id.btnZoom2x)
        btnZoom4x = findViewById(R.id.btnZoom4x)

        val btnSelesai = findViewById<Button>(R.id.btnMulaiDeteksi)
        btnSelesai.setOnClickListener {
            finish()
        }

        outputBox.visibility = View.GONE
        imageViewResult.visibility = View.GONE
        zoomButtonContainer.visibility = View.GONE

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
                Log.e("TTS", "Bahasa Indonesia tidak didukung atau data bahasa hilang!")
                Toast.makeText(this, "Bahasa Indonesia untuk TTS tidak tersedia. Coba instal data bahasa dari pengaturan perangkat.", Toast.LENGTH_LONG).show()

                val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                try {
                    startActivity(installIntent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Tidak dapat membuka pengaturan unduhan data TTS.", Toast.LENGTH_LONG).show()
                    Log.e("TTS", "Gagal membuka intent unduhan data TTS: ${e.message}")
                }

                val fallbackResult = tts!!.setLanguage(Locale.US)
                if (fallbackResult == TextToSpeech.LANG_MISSING_DATA || fallbackResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "Bahasa Inggris juga tidak didukung!")
                    Toast.makeText(this, "Tidak ada bahasa TTS yang didukung.", Toast.LENGTH_LONG).show()
                } else {
                    Log.d("TTS", "Menggunakan Bahasa Inggris sebagai fallback.")
                    tts!!.setSpeechRate(0.9f)
                    tts!!.setPitch(1.0f)
                }
            } else {
                Log.d("TTS", "TextToSpeech berhasil diinisialisasi dengan Bahasa Indonesia.")
                tts!!.setSpeechRate(0.9f)
                tts!!.setPitch(1.0f)
            }
        } else {
            Log.e("TTS", "Inisialisasi TextToSpeech gagal!")
            Toast.makeText(this, "Gagal menginisialisasi Text-to-Speech.", Toast.LENGTH_LONG).show()
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
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_BLOCK_PRODUCER) // Ini penting untuk mengelola antrean frame
                .build()
                .also {
                    it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        val currentTime = System.currentTimeMillis()
                        // Hanya proses gambar jika tidak sedang ada proses DAN sudah melewati cooldown
                        if (!isProcessingImage.get() && (currentTime - lastImageSentTime > IMAGE_PROCESSING_COOLDOWN_MILLIS)) {
                            isProcessingImage.set(true) // Set flag sedang memproses
                            lastImageSentTime = currentTime // Catat waktu pengiriman

                            sendImageToApi(imageProxy) {
                                // Callback ini dipanggil setelah proses API selesai atau gagal
                                isProcessingImage.set(false) // Reset flag
                            }
                        } else {
                            imageProxy.close() // Penting: tutup ImageProxy jika tidak digunakan
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
                cameraControl = camera.cameraControl

                val cameraInfo = camera.cameraInfo
                val zoomState = cameraInfo.zoomState

                zoomState.observe(this) { state ->
                    zoomButtonContainer.visibility = View.VISIBLE

                    val minZoom = state.minZoomRatio
                    val maxZoom = state.maxZoomRatio

                    btnZoom1x.setOnClickListener {
                        val targetZoom = 1.0f
                        cameraControl?.setZoomRatio(targetZoom.coerceIn(minZoom, maxZoom))
                    }
                    btnZoom2x.setOnClickListener {
                        val targetZoom = 2.0f
                        cameraControl?.setZoomRatio(targetZoom.coerceIn(minZoom, maxZoom))
                    }
                    btnZoom4x.setOnClickListener {
                        val targetZoom = 4.0f
                        cameraControl?.setZoomRatio(targetZoom.coerceIn(minZoom, maxZoom))
                    }
                }

                Log.d("DetectionActivity", "Kamera berhasil dimulai.")
            } catch (e: Exception) {
                Log.e("DetectionActivity", "Kamera gagal dimulai: ${e.message}")
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // Ubah tanda tangan fungsi untuk menerima callback onComplete
    private fun sendImageToApi(imageProxy: ImageProxy, onComplete: () -> Unit) {
        val originalBitmap = imageProxy.toBitmap()
        val rotatedBitmap = rotateBitmap(originalBitmap, imageProxy.imageInfo.rotationDegrees)

        val bitmapConfig = rotatedBitmap.config ?: Bitmap.Config.ARGB_8888
        val bitmapForDisplay = rotatedBitmap.copy(bitmapConfig, true)

        val byteArrayOutputStream = ByteArrayOutputStream()
        // Mengurangi kualitas kompresi JPEG untuk mengurangi ukuran dan waktu transmisi
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream) // Dari 80 ke 70
        val imageBytes = byteArrayOutputStream.toByteArray()

        imageProxy.close() // Tutup ImageProxy sesegera mungkin

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val requestBody = imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull(), 0, imageBytes.size)
                val multipartBody = MultipartBody.Part.createFormData("file", "image.jpg", requestBody)

                val response = RetrofitClient.apiService.uploadImage(multipartBody)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        val predictionResponse = response.body()
                        predictionResponse?.let {
                            if (it.detections.isNotEmpty()) {
                                outputBox.visibility = View.VISIBLE
                                previewView.visibility = View.GONE
                                imageViewResult.visibility = View.VISIBLE
                                zoomButtonContainer.visibility = View.GONE

                                val topDetection = it.detections.maxByOrNull { d -> d.confidence }

                                topDetection?.let { detection ->
                                    val label = detection.class_name
                                    val confidence = detection.confidence
                                    val infoText: String

                                    tvLabel.text = "Rambu: $label"

                                    displayImageWithBoxes(bitmapForDisplay, it.detections)

                                    if (!spokenSigns.contains(label)) {
                                        spokenSigns.add(label)

                                        when (label) {
                                            "AreaPutarBalik" -> {
                                                infoText = "Area untuk putar balik."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Area Putar Balik. $infoText")
                                            }
                                            "dilarangBelokKanan" -> {
                                                infoText = "Dilarang belok kanan."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Dilarang Belok Kanan. $infoText")
                                            }
                                            "dilarangBelokKiri" -> {
                                                infoText = "Dilarang belok kiri."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Dilarang Belok Kiri. $infoText")
                                            }
                                            "dilarangBerhenti" -> {
                                                infoText = "Dilarang berhenti di area ini."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Dilarang Berhenti. $infoText")
                                            }
                                            "dilarangParkir" -> {
                                                infoText = "Dilarang parkir di area ini."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Dilarang Parkir. $infoText")
                                            }
                                            "dilarangPutarBalik" -> {
                                                infoText = "Dilarang putar balik."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Dilarang Putar Balik. $infoText")
                                            }
                                            "hatihati" -> {
                                                infoText = "Waspada! Perhatikan sekeliling."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Hati-Hati. $infoText")
                                            }
                                            "isyaratLaluLintas" -> {
                                                infoText = "Perhatikan isyarat lampu lalu lintas."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Isyarat Lalu Lintas. $infoText")
                                            }
                                            "jalurKeretaApi" -> {
                                                infoText = "Waspada perlintasan kereta api."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Jalur Kereta Api. $infoText")
                                            }
                                            "jalurSepeda" -> {
                                                infoText = "Area khusus sepeda."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Jalur Sepeda. $infoText")
                                            }
                                            "laranganLebih30Km" -> {
                                                infoText = "Kecepatan maksimal 30 kilometer per jam."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Larangan Lebih 30 Km. $infoText")
                                            }
                                            "laranganMenyalip" -> {
                                                infoText = "Dilarang menyalip kendaraan lain."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Larangan Menyalip. $infoText")
                                            }
                                            "pejalanKakiAnakAnak" -> {
                                                infoText = "Waspada anak-anak di area pejalan kaki."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Pejalan Kaki Anak Anak. $infoText")
                                            }
                                            "penyempitanJalan-jembatan" -> {
                                                infoText = "Jalan menyempit atau ada jembatan."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Penyempitan Jalan atau Jembatan. $infoText")
                                            }
                                            "peringatan-pejalanKakiZebraCross" -> {
                                                infoText = "Waspada penyeberangan pejalan kaki (zebra cross)."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Peringatan Pejalan Kaki Zebra Cross. $infoText")
                                            }
                                            "perintahPejalanKaki" -> {
                                                infoText = "Wajib bagi pejalan kaki."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Perintah Pejalan Kaki. $infoText")
                                            }
                                            "persimpanganEmpat" -> {
                                                infoText = "Waspada persimpangan empat."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Persimpangan Empat. $infoText")
                                            }
                                            "persimpanganEmpatPrioritas" -> {
                                                infoText = "Persimpangan empat dengan prioritas."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Persimpangan Empat Prioritas. $infoText")
                                            }
                                            "persimpanganTigaKanan" -> {
                                                infoText = "Waspada persimpangan T-kanan."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Persimpangan Tiga Kanan. $infoText")
                                            }
                                            "persimpanganTigaKiri" -> {
                                                infoText = "Waspada persimpangan T-kiri."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Persimpangan Tiga Kiri. $infoText")
                                            }
                                            "simpangTigaKananPrioritas" -> {
                                                infoText = "Simpang tiga kanan dengan prioritas."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Simpang Tiga Kanan Prioritas. $infoText")
                                            }
                                            "simpangTigaKiriPrioritas" -> {
                                                infoText = "Simpang tiga kiri dengan prioritas."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu Simpang Tiga Kiri Prioritas. $infoText")
                                            }
                                            else -> {
                                                infoText = "Informasi rambu tidak tersedia."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu $label terdeteksi. Informasi tidak tersedia.")
                                            }
                                        }
                                    } else {
                                        Log.d("TTS_ONCE", "Rambu '$label' terdeteksi lagi, tapi sudah pernah diucapkan.")
                                        when (label) {
                                            "AreaPutarBalik" -> infoText = "Area untuk putar balik."
                                            "dilarangBelokKanan" -> infoText = "Dilarang belok kanan."
                                            "dilarangBelokKiri" -> infoText = "Dilarang belok kiri."
                                            "dilarangBerhenti" -> infoText = "Dilarang berhenti di area ini."
                                            "dilarangParkir" -> infoText = "Dilarang parkir di area ini."
                                            "dilarangPutarBalik" -> infoText = "Dilarang putar balik."
                                            "hatihati" -> infoText = "Waspada! Perhatikan sekeliling."
                                            "isyaratLaluLintas" -> infoText = "Perhatikan isyarat lampu lalu lintas."
                                            "jalurKeretaApi" -> infoText = "Waspada perlintasan kereta api."
                                            "jalurSepeda" -> infoText = "Area khusus sepeda."
                                            "laranganLebih30Km" -> infoText = "Kecepatan maksimal 30 kilometer per jam."
                                            "laranganMenyalip" -> infoText = "Dilarang menyalip kendaraan lain."
                                            "pejalanKakiAnakAnak" -> infoText = "Waspada anak-anak di area pejalan kaki."
                                            "penyempitanJalan-jembatan" -> infoText = "Jalan menyempit atau ada jembatan."
                                            "peringatan-pejalanKakiZebraCross" -> infoText = "Waspada penyeberangan pejalan kaki (zebra cross)."
                                            "perintahPejalanKaki" -> infoText = "Wajib bagi pejalan kaki."
                                            "persimpanganEmpat" -> infoText = "Waspada persimpangan empat."
                                            "persimpanganEmpatPrioritas" -> infoText = "Persimpangan empat dengan prioritas."
                                            "persimpanganTigaKanan" -> infoText = "Waspada persimpangan T-kanan."
                                            "persimpanganTigaKiri" -> infoText = "Waspada persimpangan T-kiri."
                                            "simpangTigaKananPrioritas" -> infoText = "Simpang tiga kanan dengan prioritas."
                                            "simpangTigaKiriPrioritas" -> infoText = "Simpang tiga kiri dengan prioritas."
                                            else -> infoText = "Informasi rambu tidak tersedia."
                                        }
                                        tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                    }
                                }
                            } else {
                                outputBox.visibility = View.GONE
                                imageViewResult.visibility = View.GONE
                                previewView.visibility = View.VISIBLE
                                zoomButtonContainer.visibility = View.VISIBLE
                                tvLabel.text = ""
                                tvOutput.text = "Tidak ada rambu lalu lintas terdeteksi."
                                spokenSigns.clear() // Hapus semua rambu yang sudah diucapkan ketika tidak ada deteksi
                            }
                        }
                    } else {
                        val errorBody = response.errorBody()?.string()
                        Log.e("DetectionActivity", "API Error: ${response.code()} - $errorBody")
                        Toast.makeText(this@DetectionActivity, "API Error: ${response.code()} - $errorBody", Toast.LENGTH_LONG).show()
                        outputBox.visibility = View.GONE
                        imageViewResult.visibility = View.GONE
                        previewView.visibility = View.VISIBLE
                        zoomButtonContainer.visibility = View.VISIBLE
                        tvLabel.text = ""
                        tvOutput.text = "Terjadi kesalahan pada API."
                        spokenSigns.clear() // Hapus semua rambu yang sudah diucapkan saat ada error
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("DetectionActivity", "Exception during API call: ${e.message}")
                    Toast.makeText(this@DetectionActivity, "Error koneksi: ${e.message}", Toast.LENGTH_LONG).show()
                    outputBox.visibility = View.GONE
                    imageViewResult.visibility = View.GONE
                    previewView.visibility = View.VISIBLE
                    zoomButtonContainer.visibility = View.VISIBLE
                    tvLabel.text = ""
                    tvOutput.text = "Gagal terhubung ke server."
                    spokenSigns.clear() // Hapus semua rambu yang sudah diucapkan saat ada error
                }
            } finally {
                // Pastikan callback onComplete dipanggil di sini untuk mereset AtomicBoolean
                onComplete()
            }
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

    private fun displayImageWithBoxes(originalBitmap: Bitmap, detections: List<Detection>) {
        val mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        val textPaint = Paint().apply {
            color = Color.BLUE
            textSize = 30f
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        imageViewResult.post {
            for (detection in detections) {
                val x1 = detection.box[0]
                val y1 = detection.box[1]
                val x2 = detection.box[2]
                val y2 = detection.box[3]

                Log.d("BoundingBox", "Final Box on Bitmap: x1=$x1, y1=$y1, x2=$x2, y2=$y2 " +
                        "Bitmap size: ${mutableBitmap.width}x${mutableBitmap.height}")

                canvas.drawRect(x1, y1, x2, y2, paint)

                val text = "${detection.class_name} (${String.format("%.2f", detection.confidence)})"
                val textWidth = textPaint.measureText(text)

                var textX = x1
                if (textX + textWidth > mutableBitmap.width) {
                    textX = mutableBitmap.width - textWidth - 5f
                }
                if (textX < 0) {
                    textX = 5f
                }

                var textY = y1 - 10f
                if (textY < textPaint.textSize) {
                    textY = y2 + textPaint.textSize + 10f
                }
                if (textY > mutableBitmap.height - 5f) {
                    textY = y1 - 10f
                }

                canvas.drawText(text, textX, textY, textPaint)
            }
            imageViewResult.setImageBitmap(mutableBitmap)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
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