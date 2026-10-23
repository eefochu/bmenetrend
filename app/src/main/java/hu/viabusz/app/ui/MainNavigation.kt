package hu.viabusz.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import hu.viabusz.app.R

enum class MainTab { MAP, PLANNER }

@Composable
fun MainNavigation(tab: MainTab, onTab: (MainTab)->Unit, map: @Composable ()->Unit, planner: @Composable ()->Unit) {
    val saved = rememberSaveableStateHolder()
    BackHandler(enabled = tab == MainTab.PLANNER) { onTab(MainTab.MAP) }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.navigationBars)) {
                saved.SaveableStateProvider(tab.name) {
                    when(tab) { MainTab.MAP -> map(); MainTab.PLANNER -> planner() }
                }
            }
            NavigationBar {
                MainTab.entries.forEach { item ->
                    val label = stringResource(if (item == MainTab.MAP) R.string.planner_nav_map else R.string.planner_nav_plan)
                    NavigationBarItem(selected = tab == item, onClick = { onTab(item) },
                        icon = { Icon(if (item == MainTab.MAP) Icons.Rounded.Map else Icons.Rounded.Route, label) }, label = { Text(label) })
                }
            }
        }
    }
}
