package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.ui.graphics.vector.ImageVector

object SimBridgeRoutes {
    const val ROUTE_DASHBOARD = "dashboard"
    const val ROUTE_CALL = "call"
    const val ROUTE_SMS = "sms"
    const val ROUTE_INTERNET = "internet"
}

data class TopLevelDestination(
    val route: String,
    val labelFa: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

val simBridgeDestinations = listOf(
    TopLevelDestination(
        route = SimBridgeRoutes.ROUTE_DASHBOARD,
        labelFa = "پل ارتباطی",
        selectedIcon = Icons.Filled.Hub,
        unselectedIcon = Icons.Outlined.Hub,
        testTag = "nav_dashboard"
    ),
    TopLevelDestination(
        route = SimBridgeRoutes.ROUTE_CALL,
        labelFa = "تماس",
        selectedIcon = Icons.Filled.Call,
        unselectedIcon = Icons.Outlined.Call,
        testTag = "nav_call"
    ),
    TopLevelDestination(
        route = SimBridgeRoutes.ROUTE_SMS,
        labelFa = "پیامک",
        selectedIcon = Icons.Filled.Sms,
        unselectedIcon = Icons.Outlined.Sms,
        testTag = "nav_sms"
    ),
    TopLevelDestination(
        route = SimBridgeRoutes.ROUTE_INTERNET,
        labelFa = "اینترنت",
        selectedIcon = Icons.Filled.WifiTethering,
        unselectedIcon = Icons.Outlined.WifiTethering,
        testTag = "nav_internet"
    )
)
