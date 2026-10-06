package com.exodia.batteryalert

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.exodia.batteryalert.ui.monitor.BatteryMonitorScreen
import com.exodia.batteryalert.ui.monitor.BatteryMonitorViewModel
import com.exodia.batteryalert.ui.theme.BatteryAlertTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); WindowCompat.setDecorFitsSystemWindows(window, false); WindowCompat.getInsetsController(window, window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.systemBars()); val transport = (application as BatteryAlertApp).container.transport; setContent { BatteryAlertTheme { val vm: BatteryMonitorViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory { @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = BatteryMonitorViewModel(transport) as T }); BatteryMonitorScreen(vm) } } }
}
