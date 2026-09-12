package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        PindoramaHomeViewModel.State.Error -> Text(
            stringResource(MR.strings.internal_error),
            modifier = modifier.padding(24.dp),
        )
        is PindoramaHomeViewModel.Success -> LazyColumn(
            modifier = modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .clipToBounds(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Pindorama!",
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontFamily = FontFamily.Serif,
                            fontSize = MaterialTheme.typography.displaySmall.fontSize * 0.9f,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(MR.strings.pindorama_home_greeting),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.continueReading?.let { (history, chapter) ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        EditorialTitle(stringResource(MR.strings.pindorama_continue_reading))
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            MangaCover.Book(history.coverData, modifier = Modifier.fillMaxWidth(0.38f))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    history.title,
                                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
                                )
                                Text(
                                    "Capítulo ${history.chapterNumber}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (chapter?.lastPageRead ?: 0L > 0L) {
                                    Text(
                                        "Página ${chapter!!.lastPageRead + 1}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Button(onClick = {
                                    onResume(history.mangaId, history.chapterId)
                                }) { Text(stringResource(MR.strings.pindorama_resume)) }
                            }
                        }
                    }
                }
            }
            if (state.recentUpdates.isNotEmpty()) {
                item { SectionTitle(stringResource(MR.strings.pindorama_recent_updates), onOpenUpdates) }
                items(state.recentUpdates) { update ->
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { onOpenManga(update.mangaId) }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MangaCover.Book(update.coverData, modifier = Modifier.fillMaxWidth(0.20f))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    update.mangaTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    update.chapterName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    EditorialTitle(stringResource(MR.strings.pindorama_activity))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        ActivityFigure(
                            state.activity.chaptersRead,
                            stringResource(MR.strings.pindorama_chapters_last_week),
                            Modifier.weight(1f),
                        )
                        ActivityFigure(
                            state.activity.worksRead,
                            stringResource(MR.strings.pindorama_works_last_week),
                            Modifier.weight(1f),
                        )
                    }
                }
            }
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onOpenLibrary) { Text(stringResource(MR.strings.label_library)) }
                    Button(onClick = onOpenBrowse) { Text(stringResource(MR.strings.browse)) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, onSeeAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = FontFamily.Serif,
                fontSize = 20.sp,
                lineHeight = 26.sp,
            ),
        )
        TextButton(
            onClick = onSeeAll,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        ) { Text(stringResource(MR.strings.pindorama_see_all)) }
    }
}

@Composable
private fun EditorialTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
    )
}

@Composable
private fun ActivityFigure(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Serif),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
