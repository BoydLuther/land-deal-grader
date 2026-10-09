package com.parcelgrade.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.parcelgrade.app.ui.AppViewModel
import com.parcelgrade.app.ui.HomeScreen
import com.parcelgrade.app.ui.ProgressScreen
import com.parcelgrade.app.ui.ReportScreen
import com.parcelgrade.app.ui.Screen
import com.parcelgrade.app.ui.SettingsScreen
import com.parcelgrade.app.ui.theme.ParcelGradeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ParcelGradeTheme { App() } }
    }
}

@Composable
private fun App(vm: AppViewModel = viewModel()) {
    BackHandler(enabled = vm.screen != Screen.HOME) { vm.back() }
    when (vm.screen) {
        Screen.HOME -> HomeScreen(vm)
        Screen.PROGRESS -> ProgressScreen(vm)
        Screen.REPORT -> ReportScreen(vm)
        Screen.SETTINGS -> SettingsScreen(vm)
    }
}
