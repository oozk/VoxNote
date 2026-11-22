package com.offlinetranscriber.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch

data class Dest(val route: String, val label: String, val icon: ImageVector)

val MainDestinations = listOf(
    Dest("home", "Home", Icons.Filled.Home),
    Dest("record", "Record", Icons.Filled.Mic),
    Dest("transcribe", "Transcribe", Icons.Filled.Subtitles),
    Dest("vocabulary", "My Vocabulary", Icons.Filled.Spellcheck),
    Dest("models", "Models", Icons.Filled.Memory),
    Dest("history", "History", Icons.Filled.History),
    Dest("settings", "Settings", Icons.Filled.Settings),
)
private val BottomBar = listOf("home", "record", "transcribe", "vocabulary", "history")

/** Gives every screen access to navigation, snackbars and the app ViewModel. */
class AppScope(val nav: NavHostController, val snackbar: SnackbarHostState, val appVm: AppViewModel, val openDrawer: () -> Unit) {
    fun go(route: String) = nav.navigate(route) { launchSingleTop = true }
    fun goTop(route: String) = nav.navigate(route) {
        popUpTo("home") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

val LocalApp = staticCompositionLocalOf<AppScope> { error("no app scope") }

@Composable
fun AppRoot(appVm: AppViewModel) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val appScope = remember { AppScope(nav, snackbar, appVm) { scope.launch { drawer.open() } } }
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route?.substringBefore('?')?.substringBefore('/')
    val busy by appVm.busy.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { appVm.routes.collect { r -> nav.navigate(r) { launchSingleTop = true } } }
    LaunchedEffect(Unit) { for (m in appVm.messages) snackbar.showSnackbar(m) }

    CompositionLocalProvider(LocalApp provides appScope) {
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                ModalDrawerSheet {
                    Column(Modifier.padding(16.dp)) {
                        Text("VoxNote", style = MaterialTheme.typography.titleLarge)
                        Text("Everything stays on this device", style = MaterialTheme.typography.bodySmall)
                    }
                    MainDestinations.forEach { d ->
                        NavigationDrawerItem(
                            label = { Text(d.label, fontWeight = FontWeight.Medium) },
                            icon = { Icon(d.icon, null) },
                            selected = currentRoute == d.route,
                            onClick = { scope.launch { drawer.close() }; appScope.goTop(d.route) },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            },
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    if (currentRoute in BottomBar || currentRoute == null) NavigationBar {
                        MainDestinations.filter { it.route in BottomBar }.forEach { d ->
                            NavigationBarItem(
                                selected = currentRoute == d.route,
                                onClick = { appScope.goTop(d.route) },
                                icon = { Icon(d.icon, null) },
                                label = { Text(if (d.route == "vocabulary") "Vocabulary" else d.label, maxLines = 1) },
                            )
                        }
                    }
                },
            ) { padding ->
                NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
                    composable("home") { HomeScreen() }
                    composable("record?autostart={autostart}", arguments = listOf(navArgument("autostart") { type = NavType.BoolType; defaultValue = false })) {
                        RecordScreen(autoStart = it.arguments?.getBoolean("autostart") == true)
                    }
                    composable("live") { LiveScreen() }
                    composable("transcribe?job={job}", arguments = listOf(navArgument("job") { type = NavType.LongType; defaultValue = -1L })) {
                        TranscribeScreen(it.arguments?.getLong("job")?.takeIf { id -> id > 0 })
                    }
                    composable("vocabulary") { VocabularyScreen() }
                    composable("vocabulary/bulk") { BulkAddScreen() }
                    composable("models") { ModelsScreen() }
                    composable("history") { HistoryScreen() }
                    composable("settings") { SettingsScreen() }
                    composable("transcript/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                        TranscriptScreen(it.arguments!!.getLong("id"))
                    }
                }
            }
        }
        busy?.let { b ->
            AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text(b.message) }, text = {
                Column {
                    Spacer(Modifier.height(8.dp))
                    if (b.fraction != null) LinearProgressIndicator(progress = { b.fraction }, modifier = Modifier.fillMaxWidth())
                    else CircularProgressIndicator()
                }
            })
        }
    }
}

/** Standard top bar with the drawer button and optional actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String, showBack: Boolean = false, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    val app = LocalApp.current
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            if (showBack) IconButton(onClick = { app.nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            } else IconButton(onClick = app.openDrawer) { Icon(Icons.Filled.Menu, "Menu") }
        },
        actions = {
            actions()
            Icon(Icons.Filled.AirplanemodeActive, "Works offline", Modifier.padding(end = 12.dp), tint = MaterialTheme.colorScheme.primary)
        },
    )
}
