package eu.kanade.domain.manga.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.core.security.PrivateContentSession
import eu.kanade.tachiyomi.core.security.PrivateContentSessionState
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class ToggleMangaPrivacy(
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(
        expected: Manga,
        session: PrivateContentSession,
        generation: Long = LibraryOperationCoordinator.shared.generation,
    ): Result {
        val coordinator = LibraryOperationCoordinator.shared
        return coordinator.operation {
            if (generation != coordinator.generation) return@operation Result.Unchanged
            val current = mangaRepository.getMangaByUrlAndSourceId(expected.url, expected.source)
                ?.takeIf { it.id == expected.id && it.source == expected.source && it.url == expected.url }
                ?: return@operation Result.Unchanged
            if (current.isPrivate && session.state.value != PrivateContentSessionState.UNLOCKED) {
                return@operation Result.Unchanged
            }
            if (!mangaRepository.update(MangaUpdate(id = current.id, isPrivate = !current.isPrivate))) {
                return@operation Result.Failed
            }
            Result.Changed(
                exitPrivateSession = !current.isPrivate && session.state.value != PrivateContentSessionState.UNLOCKED,
            )
        }
    }

    sealed interface Result {
        data object Unchanged : Result
        data object Failed : Result
        data class Changed(val exitPrivateSession: Boolean) : Result
    }
}
