package com.example.home_traffic.network

import com.example.home_traffic.data.PredictionResponse
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ApiService {
    @Multipart
    @POST("predict/") // Sesuaikan dengan path endpoint di FastAPI Anda
    suspend fun uploadImage(@Part file: MultipartBody.Part): Response<PredictionResponse>
}