package dev.silver.burble.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.silver.burble.R

/** What Burble ships, under which licence, and the licence's text in assets/licences/. */
private class Licensed(val name: String, val licence: String, val file: String)

/**
 * Every library in the APK. A new implementation dependency adds itself here; Apache 2.0 covers
 * AndroidX, Compose, the Material icons, Kotlin, kotlinx.coroutines, OkHttp and Okio.
 */
private val LICENSED = listOf(
    Licensed("Burble", "GNU General Public License 3.0", "GPL-3.0.txt"),
    Licensed("Google Sans Flex", "SIL Open Font License 1.1", "GoogleSansFlex-OFL.txt"),
    Licensed("AndroidX, Jetpack Compose and Material icons", "Apache License 2.0", "Apache-2.0.txt"),
    Licensed("Kotlin and kotlinx.coroutines", "Apache License 2.0", "Apache-2.0.txt"),
    Licensed("OkHttp and Okio", "Apache License 2.0", "Apache-2.0.txt"),
)

/** Burble's licence and the open-source licences of what it bundles; a tap shows the full text. */
@Composable
fun Licences(onBack: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    open?.let { i ->
        LicenceText(LICENSED[i]) { open = null }
        return
    }
    SubScreen(stringResource(R.string.licence), onBack) {
        item {
            Group(LICENSED.mapIndexed { i, l -> linkRow(Icons.Outlined.Description, l.name, l.licence) { open = i } })
        }
    }
}

@Composable
private fun LicenceText(l: Licensed, onBack: () -> Unit) {
    val assets = LocalContext.current.assets
    val text = remember(l.file) { runCatching { assets.open("licences/${l.file}").bufferedReader().use { it.readText() } }.getOrElse { l.licence } }
    SubScreen(l.name, onBack) {
        item { Text(text, Modifier.padding(horizontal = 8.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
    }
}
