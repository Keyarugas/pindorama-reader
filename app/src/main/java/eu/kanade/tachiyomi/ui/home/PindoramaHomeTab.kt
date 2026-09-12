package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object PindoramaHomeTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(
            index = 0u,
            title = stringResource(MR.strings.pindorama_home),
            icon = painterResource(R.drawable.ic_pindorama),
        )

    override suspend fun onReselect(navigator: Navigator) = Unit

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val tabNavigator = LocalTabNavigator.current
        val context = LocalContext.current
        val viewModel = metroViewModel<PindoramaHomeViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        PindoramaHomeScreen(
            state = state,
            onResume = { mangaId, chapterId ->
                context.startActivity(ReaderActivity.newIntent(context, mangaId, chapterId))
            },
            onOpenManga = { navigator.push(MangaScreen(it)) },
            onOpenUpdates = { tabNavigator.current = UpdatesTab },
            onOpenLibrary = { tabNavigator.current = LibraryTab },
            onOpenBrowse = { tabNavigator.current = BrowseTab },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
