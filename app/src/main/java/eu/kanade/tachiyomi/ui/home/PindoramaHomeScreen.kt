package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun PindoramaHomeScreen(
    state: PindoramaHomeViewModel.State,
    onResume: (Long, Long) -> Unit,
    onOpenManga: (Long) -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenBrowse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        PindoramaHomeViewModel.State.Loading -> CircularProgressIndicator(modifier = modifier.padding(24.dp))
        PindoramaHomeViewModel.State.Error -> Text(stringResource(MR.strings.internal_error), modifier = modifier.padding(24.dp))
        is PindoramaHomeViewModel.Success -> LazyColumn(
            modifier = modifier,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column {
                    Text("Pindorama!", style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(MR.strings.pindorama_home_greeting), style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.continueReading?.let { (history, chapter) ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            MangaCover.Book(history.coverData, modifier = Modifier.fillMaxWidth(0.25f))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(MR.strings.pindorama_continue_reading), style = MaterialTheme.typography.titleMedium)
                                Text(history.title, maxLines = 2)
                                Text("Capítulo ${history.chapterNumber}")
                                if (chapter?.lastPageRead ?: 0L > 0L) Text("Página ${chapter!!.lastPageRead + 1}")
                                Button(onClick = { onResume(history.mangaId, history.chapterId) }) { Text(stringResource(MR.strings.pindorama_resume)) }
                            }
                        }
                    }
                }
            }
            if (state.recentUpdates.isNotEmpty()) {
                item { SectionTitle(stringResource(MR.strings.pindorama_recent_updates), onOpenUpdates) }
                items(state.recentUpdates) { update ->
                    Card(onClick = { onOpenManga(update.mangaId) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MangaCover.Book(update.coverData, modifier = Modifier.fillMaxWidth(0.16f))
                            Column {
                                Text(update.mangaTitle, maxLines = 1)
                                Text(update.chapterName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            item {
                Text(stringResource(MR.strings.pindorama_activity), style = MaterialTheme.typography.titleLarge)
                Text("${state.activity.chaptersRead} ${stringResource(MR.strings.pindorama_chapters_last_week)}")
                Text("${state.activity.worksRead} ${stringResource(MR.strings.pindorama_works_last_week)}")
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenLibrary) { Text(stringResource(MR.strings.label_library)) }
                    Button(onClick = onOpenBrowse) { Text(stringResource(MR.strings.browse)) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, onSeeAll: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        androidx.compose.material3.TextButton(onClick = onSeeAll) { Text(stringResource(MR.strings.pindorama_see_all)) }
    }
}
