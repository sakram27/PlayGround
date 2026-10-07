package com.freqdroid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.freqdroid.app.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val vm: AppViewModel = viewModel()
                val nav = rememberNavController()
                Scaffold(
                    bottomBar = { BottomNav(nav) },
                    containerColor = Color(0xFF0B0E11)
                ) { pad ->
                    Box(Modifier.padding(pad)) {
                        NavHost(nav, startDestination = "dashboard") {
                            composable("dashboard") { DashboardScreen(vm) }
                            composable("trades") { TradesScreen(vm) }
                            composable("bot") { BotScreen(vm) }
                            composable("settings") { SettingsScreen(vm) }
                        }
                    }
                }
            }
        }
    }
}
