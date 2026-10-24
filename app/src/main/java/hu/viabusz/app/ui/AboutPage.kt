package hu.viabusz.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.viabusz.app.BuildConfig
import hu.viabusz.app.R

@Composable
fun AboutPage(
    onOpenUpdates: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val uriHandler = LocalUriHandler.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ui_back))
                }
                Text(stringResource(R.string.ota_about_title), style = MaterialTheme.typography.headlineSmall)
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(8.dp))
                Image(
                    painter = painterResource(R.drawable.ic_launcher),
                    contentDescription = null,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(20.dp))
                )
                Text(
                    stringResource(R.string.ui_brand),
                    style = MaterialTheme.typography.headlineMedium
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        stringResource(R.string.ota_about_version, BuildConfig.VERSION_NAME),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                Spacer(Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.ota_about_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            stringResource(R.string.ota_about_app_description),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.ui_about_action),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(stringResource(R.string.ui_about_description), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.ui_about_live_data), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.volan_about), style = MaterialTheme.typography.bodySmall)

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        TextButton(
                            onClick = { runCatching { uriHandler.openUri("https://mavplusz.hu/") } },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.volan_source_link))
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                            }
                        }
                        TextButton(
                            onClick = { runCatching { uriHandler.openUri("https://openfreemap.org/") } },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.map_provider_source))
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                            }
                        }
                        TextButton(
                            onClick = { runCatching { uriHandler.openUri("https://github.com/komoot/photon") } },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.search_places_source))
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                            }
                        }
                        TextButton(
                            onClick = { runCatching { uriHandler.openUri("https://viabusz.biatorbagy.hu/") } },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.ui_viabusz_website))
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                            }
                        }
                        TextButton(
                            onClick = { runCatching { uriHandler.openUri("https://kanyargo.paty.hu/") } },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.ui_kanyargo_website))
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                            }
                        }
                        TextButton(
                            onClick = { runCatching { uriHandler.openUri("https://homm.hu/helyi-kozlekedes/") } },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.ui_homm_website))
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = onOpenUpdates,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                ) {
                    Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.ota_about_updates_button))
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
