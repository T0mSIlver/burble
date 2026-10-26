package dev.silver.burble.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.silver.burble.R
import java.util.Locale

/** Common dictation languages, as BCP-47 tags. Rambler takes any of them; the list stays short. */
val Languages = listOf(
    "en-US", "en-GB", "en-IN", "en-AU", "en-CA",
    "fr-FR", "fr-CA", "de-DE", "es-ES", "es-US", "es-MX", "it-IT", "pt-BR", "pt-PT",
    "nl-NL", "pl-PL", "sv-SE", "da-DK", "nb-NO", "fi-FI", "cs-CZ", "ro-RO", "tr-TR", "el-GR",
    "ru-RU", "uk-UA", "ar-SA", "he-IL", "hi-IN", "id-ID", "vi-VN", "th-TH", "ja-JP", "ko-KR",
)

/** "English (United States)" in the phone's language. */
fun languageName(tag: String, locale: Locale = Locale.getDefault()): String =
    Locale.forLanguageTag(tag).getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }

/**
 * A page over Advanced, as Android's own language settings: tick the languages you speak; the primary one
 * is the one Burble expects first, chosen with Make primary rather than by the order of ticking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagesScreen(current: List<String>, onDone: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var picked by rememberSaveable { mutableStateOf(current) }
    // The ones in use first, so a long list doesn't hide them.
    val list = remember(current) { current + Languages.filterNot { it in current } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.languages)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(android.R.string.cancel)) }
                },
                actions = { TextButton(onClick = { onDone(picked) }) { Text(stringResource(R.string.done)) } },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                Text(
                    stringResource(R.string.languages_hint),
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(list) { tag ->
                val on = tag in picked
                val primary = picked.firstOrNull() == tag
                // The last language can't go: dictation needs one.
                val locked = on && picked.size == 1
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .toggleable(on, enabled = !locked, role = Role.Checkbox) {
                            picked = if (it) picked + tag else picked - tag
                        }
                        .padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = on, onCheckedChange = null, enabled = !locked)
                    Text(
                        languageName(tag),
                        Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    when {
                        primary -> Text(
                            stringResource(R.string.languages_primary),
                            Modifier.padding(end = 12.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        on -> TextButton(onClick = { picked = listOf(tag) + (picked - tag) }) {
                            Text(stringResource(R.string.languages_make_primary))
                        }
                    }
                }
            }
        }
    }
}
