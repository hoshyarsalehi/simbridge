package com.example

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.model.CallBridgeState
import com.example.model.DeviceRole
import com.example.ui.MainViewModel
import com.example.ui.components.IncomingCallBridgeDialog
import com.example.ui.navigation.SimBridgeRoutes
import com.example.ui.navigation.simBridgeDestinations
import com.example.ui.screens.CallScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.InternetShareScreen
import com.example.ui.screens.SmsScreen
import com.example.ui.theme.SimBridgeTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShareIntent(intent)

        setContent {
            SimBridgeTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SimBridgeApp(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSystemStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank()) {
                viewModel.handleIncomingSharedText(text)
            }
        }
    }
}

@Composable
fun SimBridgeApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: SimBridgeRoutes.ROUTE_DASHBOARD
    val snackbarHostState = remember { SnackbarHostState() }

    val requiredPermissions = remember(uiState.settings.deviceRole) {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (uiState.settings.deviceRole == DeviceRole.HOST_SIM) {
                add(Manifest.permission.READ_PHONE_STATE)
                add(Manifest.permission.READ_CALL_LOG)
                add(Manifest.permission.CALL_PHONE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    add(Manifest.permission.ANSWER_PHONE_CALLS)
                }
                add(Manifest.permission.RECEIVE_SMS)
                add(Manifest.permission.READ_SMS)
                add(Manifest.permission.SEND_SMS)
            }
        }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshSystemStatus()
    }

    LaunchedEffect(uiState.statusBannerMessage) {
        val message = uiState.statusBannerMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.dismissBannerMessage()
        }
    }

    if (uiState.settings.deviceRole == DeviceRole.COMPANION_NO_SIM &&
        uiState.activeCallState == CallBridgeState.INCOMING_RINGING
    ) {
        IncomingCallBridgeDialog(
            callerLabel = uiState.incomingCallerLabel,
            onAnswerCall = { viewModel.answerCallViaHost(startVoiceBridge = true) },
            onEndCall = viewModel::endCallViaHost,
            onDismissAlertOnly = viewModel::dismissIncomingCallAlert
        )
    }

    val navigateTo: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        simBridgeDestinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.labelFa
                                    )
                                },
                                label = { Text(dest.labelFa) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        simBridgeDestinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTo(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.labelFa
                                    )
                                },
                                label = { Text(dest.labelFa) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                NavHost(
                    navController = navController,
                    startDestination = SimBridgeRoutes.ROUTE_DASHBOARD,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(SimBridgeRoutes.ROUTE_DASHBOARD) {
                        DashboardScreen(
                            uiState = uiState,
                            onSelectRole = viewModel::setDeviceRole,
                            onStartHost = viewModel::startHostListening,
                            onConnectDevice = viewModel::connectToPairedHost,
                            onDisconnect = viewModel::disconnectBridge,
                            onRefreshDevices = viewModel::refreshSystemStatus,
                            onOpenBluetoothSettings = viewModel::openBluetoothSettings,
                            onRequestPermissions = {
                                permissionLauncher.launch(requiredPermissions)
                            },
                            onSendTestRing = viewModel::simulateTestIncomingRingToPeer,
                            onToggleRingOnCompanion = viewModel::toggleRingOnCompanion,
                            onToggleVibrateOnCall = viewModel::toggleVibrateOnCall,
                            onNavigateToInternet = { navigateTo(SimBridgeRoutes.ROUTE_INTERNET) }
                        )
                    }
                    composable(SimBridgeRoutes.ROUTE_CALL) {
                        CallScreen(
                            uiState = uiState,
                            onDialDigit = viewModel::onDialDigit,
                            onDialBackspace = viewModel::onDialBackspace,
                            onPlaceCall = viewModel::placeCallViaHostSim,
                            onAnswerCall = { viewModel.answerCallViaHost(startVoiceBridge = true) },
                            onEndCall = viewModel::endCallViaHost,
                            onToggleVoiceBridge = viewModel::toggleVoiceBridge,
                            onClearCallLogs = viewModel::clearCallLogs
                        )
                    }
                    composable(SimBridgeRoutes.ROUTE_SMS) {
                        SmsScreen(
                            uiState = uiState,
                            onRecipientChanged = viewModel::onSmsRecipientChanged,
                            onBodyChanged = viewModel::onSmsBodyChanged,
                            onSendSmsRelay = viewModel::sendSmsViaHostSim,
                            onRequestRecentSmsSync = viewModel::requestRecentSmsSyncFromHost,
                            onClearSmsLogs = viewModel::clearSmsLogs
                        )
                    }
                    composable(SimBridgeRoutes.ROUTE_INTERNET) {
                        InternetShareScreen(
                            uiState = uiState,
                            onSelectMode = viewModel::selectInternetShareMode,
                            onHostSsidChanged = viewModel::onHostHotspotSsidChanged,
                            onHostPassChanged = viewModel::onHostHotspotPassChanged,
                            onShareHotspotCredentials = viewModel::shareHotspotCredentialsWithPeer,
                            onOpenTetheringSettings = viewModel::openTetheringSettings,
                            onOpenWifiSettings = viewModel::openWifiSettings,
                            onOpenBluetoothSettings = viewModel::openBluetoothSettings
                        )
                    }
                }
            }
        }
    }
}
