package com.example.home_traffic

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class InformasiRambuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_informasi_rambu)

        // You can add any additional logic here if needed,
        // for example, setting up a back button in the action bar.
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Informasi Rambu"
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}