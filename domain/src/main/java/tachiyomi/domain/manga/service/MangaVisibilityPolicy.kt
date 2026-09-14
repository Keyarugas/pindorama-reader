package tachiyomi.domain.manga.service

import eu.kanade.tachiyomi.core.security.PrivateContentSessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Visibility only: never use this to exclude records from persistence or background work. */
object MangaVisibilityPolicy {
    fun isVisible(isPrivate: Boolean, session: PrivateContentSessionState): Boolean =
        !isPrivate || session == PrivateContentSessionState.UNLOCKED

    fun <T> observe(
        items: Flow<List<T>>,
        privateIds: Flow<Set<Long>?>,
        session: Flow<PrivateContentSessionState>,
        mangaId: (T) -> Long,
    ): Flow<List<T>> = combine(items, privateIds, session) { list, ids, state ->
        if (ids == null) emptyList() else filter(list, ids, state, mangaId)
    }

    fun <T> filter(
        items: List<T>,
        privateIds: Set<Long>,
        session: PrivateContentSessionState,
        mangaId: (T) -> Long,
    ): List<T> = items.filter { isVisible(mangaId(it) in privateIds, session) }
}
