package app.steady

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.steady.nav.SteadyNavHost
import app.steady.ui.theme.SteadyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = appContainer
        setContent {
            SteadyTheme {
                SteadyNavHost(container = container)
            }
        }
    }
}
