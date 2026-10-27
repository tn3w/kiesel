package kiesel

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private val model get() = (application as KieselApp).model

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val permissions = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { model.trackLocation() }

        model.load()
        if (savedInstanceState == null) intent.data?.let { model.install(listOf(it)) }
        permissions.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )
        setContent { KieselTheme { KieselScreen(model) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { model.install(listOf(it)) }
    }

    override fun onStart() {
        super.onStart()
        model.trackLocation()
    }

    override fun onStop() {
        super.onStop()
        model.releaseLocation()
    }
}
