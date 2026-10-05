package eu.kanade.tachiyomi.ui.browse.migration.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.browse.MigrateSearchScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchViewModel
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.feature.migration.list.MigrationListScreen
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator

class MigrateSearchScreen(
    private val mangaId: Long,
    private val generation: Long = LibraryOperationCoordinator.shared.generation,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        val viewModel =
            assistedMetroViewModel<MigrateSearchViewModel, MigrateSearchViewModel.Factory> { create(mangaId = mangaId) }
        val state by viewModel.state.collectAsState()

        MigrateSearchScreen(
            state = state,
            fromSourceId = state.from?.source,
            navigateUp = navigator::pop,
            onChangeSearchQuery = viewModel::updateSearchQuery,
            onSearch = { viewModel.search() },
            getManga = { viewModel.getManga(it) },
            onChangeSearchFilter = viewModel::setSourceFilter,
            onToggleResults = viewModel::toggleFilterResults,
            onClickSource = {
                navigator.push(MigrateSourceSearchScreen(state.from!!, it.id, state.searchQuery, generation))
            },
            onClickItem = {
                val migrateListScreen = navigator.items
                    .filterIsInstance<MigrationListScreen>()
                    .lastOrNull()

                if (migrateListScreen == null) {
                    viewModel.setMigrateDialog(mangaId, it, generation)
                } else {
                    migrateListScreen.addMatchOverride(current = mangaId, target = it.id, generation = generation)
                    navigator.popUntil { screen -> screen is MigrationListScreen }
                }
            },
            onLongClickItem = { navigator.push(MangaScreen(it.id, true)) },
        )

        when (val dialog = state.dialog) {
            is SearchViewModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    generation = dialog.generation,
                    // Initiated from the context of [dialog.current] so we show [dialog.target].
                    onClickTitle = { navigator.push(MangaScreen(dialog.target.id, true)) },
                    onDismissRequest = { viewModel.clearDialog() },
                    onComplete = {
                        if (navigator.lastItem is MangaScreen) {
                            val lastItem = navigator.lastItem
                            navigator.popUntil { navigator.items.contains(lastItem) }
                            navigator.push(MangaScreen(dialog.target.id))
                        } else {
                            navigator.replace(MangaScreen(dialog.target.id))
                        }
                    },
                )
            }
            else -> {}
        }
    }
}
