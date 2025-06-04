package com.example.home_traffic

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

class DetectionActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var previewView: PreviewView
    private lateinit var tvLabel: TextView
    private lateinit var tvOutput: TextView
    private lateinit var outputBox: View
    private lateinit var imageViewResult: ImageView
    private val REQUEST_CAMERA_PERMISSION = 10

    private var tts: TextToSpeech? = null

    // --- Variabel baru untuk cooldown TTS ---
    private var lastSpokenSign: String? = null
    private var lastSpokenTime: Long = 0L
    private val TTS_COOLDOWN_MILLIS = 5000L // 5 detik cooldown
    // --- Akhir variabel baru ---


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detection)

        previewView = findViewById(R.id.previewView)
        tvLabel = findViewById(R.id.tvLabel)
        tvOutput = findViewById(R.id.tvOutput)
        outputBox = findViewById(R.id.outputBox)
        imageViewResult = findViewById(R.id.imageViewResult)

        val btnSelesai = findViewById<Button>(R.id.btnMulaiDeteksi)
        btnSelesai.setOnClickListener {
            finish()
        }

        outputBox.visibility = View.GONE
        imageViewResult.visibility = View.GONE

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
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_BLOCK_PRODUCER)
                .build()
                .also {
                    it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        sendImageToApi(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
                Log.d("DetectionActivity", "Kamera berhasil dimulai.")
            } catch (e: Exception) {
                Log.e("DetectionActivity", "Kamera gagal dimulai: ${e.message}")
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun sendImageToApi(imageProxy: ImageProxy) {
        val originalBitmap = imageProxy.toBitmap()
        val rotatedBitmap = rotateBitmap(originalBitmap, imageProxy.imageInfo.rotationDegrees)

        val bitmapConfig = rotatedBitmap.config ?: Bitmap.Config.ARGB_8888
        val bitmapForDisplay = rotatedBitmap.copy(bitmapConfig, true)

        val byteArrayOutputStream = ByteArrayOutputStream()
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream)
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
                        predictionResponse?.let {
                            if (it.detections.isNotEmpty()) {
                                outputBox.visibility = View.VISIBLE
                                previewView.visibility = View.GONE
                                imageViewResult.visibility = View.VISIBLE

                                val topDetection = it.detections.maxByOrNull { d -> d.confidence }

                                topDetection?.let { detection ->
                                    val label = detection.class_name
                                    val confidence = detection.confidence
                                    val infoText: String

                                    tvLabel.text = "Rambu: $label"

                                    displayImageWithBoxes(bitmapForDisplay, it.detections)

                                    // --- Logika untuk menghindari pengulangan suara ---
                                    val currentTime = System.currentTimeMillis()
                                    if (label != lastSpokenSign || (currentTime - lastSpokenTime > TTS_COOLDOWN_MILLIS)) {
                                        // Rambu berbeda ATAU rambu sama tapi sudah melewati cooldown
                                        lastSpokenSign = label
                                        lastSpokenTime = currentTime

                                        when (label) {
                                            "AreaPutarBalik" -> {
                                                infoText = "Area Putar Balik."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Siapkan lajur untuk putar balik, perhatikan kendaraan lain.")
                                            }
                                            "bundaran" -> {
                                                infoText = "Area Bundaran."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Kurangi kecepatan dan beri prioritas kendaraan dari kiri atau kanan.")
                                            }
                                            "dilarangBelokKanan" -> {
                                                infoText = "Dilarang Belok Kanan."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Ikuti jalur lurus.")
                                            }
                                            "dilarangBelokKiri" -> {
                                                infoText = "Dilarang Belok Kiri."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Ikuti jalur lurus.")
                                            }
                                            "dilarangBerhenti" -> {
                                                infoText = "Dilarang Berhenti."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Tetap malaju karena area ini dilarang berhenti.")
                                            }
                                            "dilarangParkir" -> {
                                                infoText = "Dilarang Parkir."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Jaga kelancaran lalu lintas")
                                            }
                                            "dilarangPutarBalik" -> {
                                                infoText = "Dilarang Putar Balik."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Teruskan perjalanan dan jaga kelancaran lalu lintas")
                                            }
                                            "hatihati" -> {
                                                infoText = "Hati - Hati."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Perhatikan sekeliling jalan dan kecepatan Anda.")
                                            }
                                            "isyaratLaluLintas" -> {
                                                infoText = "Hati - Hati."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Ikuti aturan lampu lalu lintas, siap berhenti jika perlu.")
                                            }
                                            "jalurKeretaApi" -> {
                                                infoText = "Jalur Kereta Api."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Waspada lintasan kereta api, perhatikan suara dan sinyal.")
                                            }
                                            "jalurSepeda" -> {
                                                infoText = "Jalur Sepeda."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Berikan ruang bagi pengguna sepeda, hati-hati.")
                                            }
                                            "laranganLebih30Km" -> {
                                                infoText = "Kecepatan Maksimal 30Km"
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Patuhi batas kecepatan dan hati-hati.")
                                            }
                                            "laranganMenyalip" -> {
                                                infoText = "Dilarang Menyalip"
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Utamakan Keselamatan tetaplah di jalur Anda.")
                                            }
                                            "pejalanKakiAnakAnak" -> {
                                                infoText = "Area Pejalan Kaki Anak - Anak"
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Waspada anak-anak menyeberang, kurangi kecepatan.")
                                            }
                                            "penyempitanJalan-jembatan" -> {
                                                infoText = "Penyempitan Jalan atau terdapat Jembatan."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Kurangi kecepatan, perhatikan lebar jalan, dan berhati-hati.")
                                            }
                                            "peringatan-pejalanKakiZebraCross" -> {
                                                infoText = "Area Zebra Cross"
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Berhenti jika ada pejalan kaki menyeberang.")
                                            }
                                            "perintahPejalanKaki" -> {
                                                infoText = "Pejalan Kaki Wajib Lewat Sini."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Beri prioritas pada pejalan kaki, pastikan keselamatan mereka.")
                                            }
                                            "persimpanganEmpat" -> {
                                                infoText = "Persimpangan Empat"
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Kurangi kecepatan dan berhati-hati saat melintas.")
                                            }
                                            "persimpanganEmpatPrioritas" -> {
                                                infoText = "Persimpangan Empat dengan Prioritas."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Beri prioritas sesuai aturan persimpangan.")
                                            }
                                            "persimpanganTigaKanan" -> {
                                                infoText = "Persimpangan Tiga Kanan"
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Kurangi  kecepatan dan perhatikan lalu lintas dari kanan")
                                            }
                                            "persimpanganTigaKiri" -> {
                                                infoText = "Persimpangan Tiga Kiri"
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Kurangi kecepatan dan perhatikan lalu lintas dari kiri.")
                                            }
                                            "simpangTigaKananPrioritas" -> {
                                                infoText = "Simpang Tiga Kanan"
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Beri prioritas pada kendaraan dari arah simpang kanan.")
                                            }
                                            "simpangTigaKiriPrioritas" -> {
                                                infoText = "Simpang Tiga Kiri"
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Beri prioritas pada kendaraan dari arah simpang kiri.")
                                            }
                                            "tikunganGandaKanan" -> {
                                                infoText = "Tikungan Ganda ke Kanan"
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Kurangi kecepatan, perhatikan tikungan berurutan ke kanan.")
                                            }
                                            "tikunganKanan" -> {
                                                infoText = "Tikungan Kanan"
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Kurangi kecepatan, perhatikan tikungan ke kanan.")
                                            }
                                            "tikunganTajamGandaKanan" -> {
                                                infoText = "Tikungan Tajam Ganda ke Kanan"
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Kurangi kecepatan secara signifikan, perhatikan dua tikungan tajam berurutan ke kanan.")
                                            }
                                            "tikunganTajamKiri" -> {
                                                infoText = "Tikungan Tajam Kiri"
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("$infoText. Kurangi kecepatan secara signifikan, perhatikan tikungan tajam ke kiri.")
                                            }

                                            else -> {
                                                infoText = "Informasi rambu tidak tersedia."
                                                tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                                speakOut("Rambu $label terdeteksi. Informasi tidak tersedia.")
                                            }
                                        }
                                    } else {
                                        Log.d("TTS_COOLDOWN", "Rambu '$label' terdeteksi lagi, tapi masih dalam cooldown.")
                                        // Jika tidak diucapkan, set teks tetap, tapi jangan panggil speakOut
                                        when (label) {
                                            "AreaPutarBalik" -> infoText = "Area untuk putar balik."
                                            "bundaran" -> infoText = "Area bundaran"
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
                                            "tikunganGandaKanan" -> infoText = "Tikungan Ganda ke Kanan."
                                            "tikunganKanan" -> infoText = "Tikungan ke Kanan."
                                            "tikunganTajamGandaKanan" -> infoText = "Tikungan tajam ganda ke Kanan."
                                            "tikunganTajamKiri" -> infoText = "Tikungan tajam ke kiri."
                                            else -> infoText = "Informasi rambu tidak tersedia."
                                        }
                                        tvOutput.text = "Kepercayaan: ${String.format("%.2f", confidence * 100)}%\n$infoText"
                                    }
                                    // --- Akhir logika cooldown ---
                                }
                            } else {
                                outputBox.visibility = View.GONE
                                imageViewResult.visibility = View.GONE
                                previewView.visibility = View.VISIBLE
                                tvLabel.text = ""
                                tvOutput.text = "Tidak ada rambu lalu lintas terdeteksi."
                                // Reset cooldown jika tidak ada rambu terdeteksi
                                lastSpokenSign = null
                                lastSpokenTime = 0L
                            }
                        }
                    } else {
                        val errorBody = response.errorBody()?.string()
                        Log.e("DetectionActivity", "API Error: ${response.code()} - $errorBody")
                        Toast.makeText(this@DetectionActivity, "API Error: ${response.code()} - $errorBody", Toast.LENGTH_LONG).show()
                        outputBox.visibility = View.GONE
                        imageViewResult.visibility = View.GONE
                        previewView.visibility = View.VISIBLE
                        tvLabel.text = ""
                        tvOutput.text = "Terjadi kesalahan pada API."
                        // Reset cooldown jika ada error
                        lastSpokenSign = null
                        lastSpokenTime = 0L
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("DetectionActivity", "Exception during API call: ${e.message}")
                    Toast.makeText(this@DetectionActivity, "Error koneksi: ${e.message}", Toast.LENGTH_LONG).show()
                    outputBox.visibility = View.GONE
                    imageViewResult.visibility = View.GONE
                    previewView.visibility = View.VISIBLE
                    tvLabel.text = ""
                    tvOutput.text = "Gagal terhubung ke server."
                    // Reset cooldown jika ada error
                    lastSpokenSign = null
                    lastSpokenTime = 0L
                }
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
            val viewWidth = imageViewResult.width.toFloat()
            val viewHeight = imageViewResult.height.toFloat()

            val bitmapWidth = originalBitmap.width.toFloat()
            val bitmapHeight = originalBitmap.height.toFloat()

            // Calculate scale factor for fitCenter
            val scaleX = viewWidth / bitmapWidth
            val scaleY = viewHeight / bitmapHeight
            val scale = Math.min(scaleX, scaleY)

            // Calculate offsets to center the scaled bitmap in the ImageView
            val scaledBitmapWidth = bitmapWidth * scale
            val scaledBitmapHeight = bitmapHeight * scale
            val offsetX = (viewWidth - scaledBitmapWidth) / 2f
            val offsetY = (viewHeight - scaledBitmapHeight) / 2f

            for (detection in detections) {
                val x1_original = detection.box[0]
                val y1_original = detection.box[1]
                val x2_original = detection.box[2]
                val y2_original = detection.box[3]

                Log.d("BoundingBox", "Original Box from API: x1=$x1_original, y1=$y1_original, x2=$x2_original, y2=$y2_original")
                Log.d("BoundingBox", "Bitmap for drawing size: ${mutableBitmap.width}x${mutableBitmap.height}")

                // Draw bounding box directly on mutableBitmap
                canvas.drawRect(x1_original, y1_original, x2_original, y2_original, paint)

                val text = "${detection.class_name} (${String.format("%.2f", detection.confidence)})"
                val textWidth = textPaint.measureText(text)

                var textX = x1_original
                if (textX + textWidth > mutableBitmap.width) {
                    textX = mutableBitmap.width - textWidth - 5f
                }
                if (textX < 0) {
                    textX = 5f
                }

                var textY = y1_original - 10f
                if (textY < textPaint.textSize) {
                    textY = y2_original + textPaint.textSize + 10f
                }
                if (textY > mutableBitmap.height - 5f) {
                    textY = y1_original - 10f
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