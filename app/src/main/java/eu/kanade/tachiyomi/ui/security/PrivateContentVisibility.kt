package eu.kanade.tachiyomi.ui.security

import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.manga.service.MangaVisibilityPolicy

@Inject
@SingleIn(AppScope::class)
class PrivateContentVisibility(
    repository: MangaRepository,
) {
    // Null means not loaded: notification callers must fail closed until classification is known.
    val privateIds = repository.getPrivateMangaIds()
        .map<List<Long>, Set<Long>?> { it.toSet() }
        .stateIn(ProcessLifecycleOwner.get().lifecycleScope, SharingStarted.Eagerly, null)

    fun <T> filter(items: Flow<List<T>>, mangaId: (T) -> Long): Flow<List<T>> =
        MangaVisibilityPolicy.observe(items, privateIds, PrivateContentSessionManager.session.state, mangaId)
}
