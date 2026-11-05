package hu.hanizso.raktar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import hu.hanizso.raktar.ui.MainScreen
import hu.hanizso.raktar.ui.RaktarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RaktarTheme { MainScreen() } }
    }
}
