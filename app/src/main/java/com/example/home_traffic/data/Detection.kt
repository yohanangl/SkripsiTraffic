package com.example.home_traffic.data

data class Detection(
    val box: List<Float>,
    val confidence: Float,
    val class_id: Int,
    val class_name: String
)