package world.syncloom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import world.syncloom.ui.AuthScreen
import world.syncloom.ui.FilesScreen
import world.syncloom.ui.SettingsScreen
import world.syncloom.ui.SyncloomTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SyncloomTheme { Root(vm) } }
    }
}

@Composable
private fun Root(vm: AppViewModel) {
    val session = vm.session
    when {
        vm.restoring -> Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() } }
        session == null -> AuthScreen(vm)
        else -> Home(vm, session)
    }
}

@Composable
private fun Home(vm: AppViewModel, session: uniffi.syncloom_core.Session) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Folder, null) }, label = { Text("Files") })
            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.People, null) }, label = { Text("Shared") })
            NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
        }
    }) { pad ->
        Box(Modifier.padding(bottom = pad.calculateBottomPadding())) {
            when (tab) {
                0 -> FilesScreen(session, "Files", shared = false, onChanged = vm::refreshUser)
                1 -> FilesScreen(session, "Shared with me", shared = true)
                else -> SettingsScreen(vm)
            }
        }
    }
}
