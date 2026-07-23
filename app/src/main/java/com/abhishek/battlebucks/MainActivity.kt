package com.abhishek.battlebucks

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhishek.battlebucks.ui.leaderboard.LeaderboardScreen
import com.abhishek.battlebucks.ui.leaderboard.LeaderboardViewModel
import com.abhishek.battlebucks.ui.theme.BattleBucksTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The leaderboard is a dark game surface with a fixed palette, so the system bars stay on
        // light content instead of following the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            BattleBucksTheme(darkTheme = true, dynamicColor = false) {
                val viewModel: LeaderboardViewModel =
                    viewModel(factory = LeaderboardViewModel.Factory)

                // collectAsStateWithLifecycle rather than collectAsState, so collection stops at
                // ON_STOP and resumes at ON_START. A backgrounded screen does no rendering work and
                // holds no live subscription, while the match itself is application-scoped.
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                LeaderboardScreen(state = state)
            }
        }
    }
}
