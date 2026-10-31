package hu.app.bmenetrend.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import hu.app.bmenetrend.R

enum class MainTab { MAP, PLANNER, FAVORITES }

@Composable
fun MainNavigation(
    tab: MainTab,
    onTab: (MainTab)->Unit,
    map: @Composable ()->Unit,
    planner: @Composable ()->Unit,
    favorites: @Composable ()->Unit,
    offline: Boolean = false,
    onOfflineSettings: () -> Unit = {},
    hideBottomBarOnPlanner: Boolean = false
) {
    val saved=rememberSaveableStateHolder()
    val rail=transitWindowWidth()>=840.dp
    val compact=LocalDensity.current.fontScale>1.3f
    BackHandler(enabled=tab!=MainTab.MAP) { onTab(MainTab.MAP) }
    Surface(color=MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxSize()) {
            if(rail) Surface(color=MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.width(if(compact) 156.dp else 104.dp).fillMaxHeight().statusBarsPadding().navigationBarsPadding().selectableGroup(),
                    verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.height(16.dp))
                    MainTab.entries.forEach { item -> TransitNavigationItem(item,tab==item,{onTab(item)},Modifier.fillMaxWidth()) }
                }
            }
            Column(Modifier.weight(1f)) {
                Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.navigationBars),contentAlignment=Alignment.TopCenter) {
                    saved.SaveableStateProvider(tab.name) {
                        when (tab) {
                            MainTab.MAP -> map()
                            MainTab.PLANNER -> planner()
                            MainTab.FAVORITES -> Box(Modifier.widthIn(max=TransitDesign.contentWidth).fillMaxSize()) { favorites() }
                        }
                    }
                }
                if (offline) TextButton(onClick = onOfflineSettings, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.offline_open_settings)) }
                if(!rail && (!hideBottomBarOnPlanner || tab != MainTab.PLANNER)) Surface(color=MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Column {
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().selectableGroup().padding(horizontal=12.dp,vertical=6.dp)) {
                            MainTab.entries.forEach { item -> TransitNavigationItem(item,tab==item,{onTab(item)},
                                Modifier.weight(if(compact) { if(tab==item) 1.6f else .8f } else 1f),compact) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransitNavigationItem(item: MainTab,selected: Boolean,onClick: ()->Unit,modifier: Modifier,compact: Boolean=false) {
    val label=stringResource(when(item) { MainTab.MAP->R.string.planner_nav_map;MainTab.PLANNER->R.string.planner_nav_plan;MainTab.FAVORITES->R.string.favorites_nav })
    val fill by animateColorAsState(if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        tween(160),label="navigation selection")
    Column(modifier.heightIn(min=64.dp).semantics { if(compact) contentDescription=label }.selectable(
        selected=selected,
        interactionSource=remember { MutableInteractionSource() },
        indication=null,
        onClick=onClick,
        role=Role.Tab
    ).padding(vertical=4.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Surface(color=fill,shape=RoundedCornerShape(if(selected) 12.dp else 20.dp)) {
            Icon(when(item) { MainTab.MAP->Icons.Rounded.Map;MainTab.PLANNER->Icons.Rounded.Route;MainTab.FAVORITES->Icons.Rounded.Favorite },null,
                Modifier.padding(horizontal=20.dp,vertical=6.dp).size(24.dp),
                tint=if(selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(!compact || selected) Text(label,maxLines=1,style=MaterialTheme.typography.labelMedium,color=if(selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
