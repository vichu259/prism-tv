package tv.prism.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.prism.app.ui.AppViewModel
import tv.prism.app.ui.HomeScreen
import tv.prism.app.ui.PlayerScreen
import tv.prism.app.ui.PrismTheme
import tv.prism.app.ui.Screen
import tv.prism.app.ui.SetupScreen
import tv.prism.app.ui.VodPlayerScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Live TV: never let the screensaver kick in mid-programme.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            PrismTheme {
                val state by vm.ui.collectAsStateWithLifecycle()
                when (state.screen) {
                    Screen.Setup -> SetupScreen(vm, state)
                    Screen.Home -> HomeScreen(vm, state)
                    Screen.Player -> PlayerScreen(vm, state)
                    Screen.VodPlayer -> VodPlayerScreen(vm, state)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        vm.saveVodPosition()
        // Don't keep streaming (and using the provider's connection slot) in the background.
        vm.player.player.pause()
    }

    override fun onStart() {
        super.onStart()
        vm.player.player.play()
    }
}
