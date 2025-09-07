package com.example.gobaneye

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent // needs activity-compose dep
// (Optional) If this import fails, just delete enableEdgeToEdge() call below:
// import androidx.activity.enableEdgeToEdge

import androidx.lifecycle.viewmodel.compose.viewModel // needs lifecycle-viewmodel-compose
import com.example.gobaneye.ui.GobanScreen
import com.example.gobaneye.vm.GobanViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // If unresolved, comment out the next line (it's not required):
    // enableEdgeToEdge()

    setContent {
      val vm: GobanViewModel = viewModel()
      GobanScreen(vm)
    }
  }
}
