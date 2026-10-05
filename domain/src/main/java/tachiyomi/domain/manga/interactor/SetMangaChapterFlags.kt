package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class SetMangaChapterFlags(
    private val mangaRepository: MangaRepository,
) {

    suspend fun awaitSetDownloadedFilter(
        manga: Manga,
        flag: Long,
        generation: Long = LibraryOperationCoordinator.shared.generation,
    ): Boolean = update(manga, generation) {
        it.chapterFlags.setFlag(flag, Manga.CHAPTER_DOWNLOADED_MASK)
    }

    suspend fun awaitSetUnreadFilter(
        manga: Manga,
        flag: Long,
        generation: Long = LibraryOperationCoordinator.shared.generation,
    ): Boolean = update(manga, generation) {
        it.chapterFlags.setFlag(flag, Manga.CHAPTER_UNREAD_MASK)
    }

    suspend fun awaitSetBookmarkFilter(
        manga: Manga,
        flag: Long,
        generation: Long = LibraryOperationCoordinator.shared.generation,
    ): Boolean = update(manga, generation) {
        it.chapterFlags.setFlag(flag, Manga.CHAPTER_BOOKMARKED_MASK)
    }

    suspend fun awaitSetDisplayMode(
        manga: Manga,
        flag: Long,
        generation: Long = LibraryOperationCoordinator.shared.generation,
    ): Boolean = update(manga, generation) {
        it.chapterFlags.setFlag(flag, Manga.CHAPTER_DISPLAY_MASK)
    }

    suspend fun awaitSetSortingModeOrFlipOrder(
        manga: Manga,
        flag: Long,
        generation: Long = LibraryOperationCoordinator.shared.generation,
    ): Boolean = update(manga, generation) { current ->
        if (current.sorting == flag) {
            val direction = if (current.sortDescending()) Manga.CHAPTER_SORT_ASC else Manga.CHAPTER_SORT_DESC
            current.chapterFlags.setFlag(direction, Manga.CHAPTER_SORT_DIR_MASK)
        } else {
            current.chapterFlags
                .setFlag(flag, Manga.CHAPTER_SORTING_MASK)
                .setFlag(Manga.CHAPTER_SORT_ASC, Manga.CHAPTER_SORT_DIR_MASK)
        }
    }

    private suspend fun update(expected: Manga, generation: Long, flags: (Manga) -> Long): Boolean {
        val coordinator = LibraryOperationCoordinator.shared
        return coordinator.operation {
            if (generation != coordinator.generation) return@operation false
            val current = mangaRepository.getMangaByUrlAndSourceId(expected.url, expected.source)
                ?.takeIf { it.id == expected.id && it.source == expected.source && it.url == expected.url }
                ?: return@operation false
            mangaRepository.update(MangaUpdate(id = current.id, chapterFlags = flags(current)))
        }
    }

    // Only the coordinated defaults operation uses this primitive, including its entire batch.
    internal suspend fun awaitSetAllFlags(
        mangaId: Long,
        unreadFilter: Long,
        downloadedFilter: Long,
        bookmarkedFilter: Long,
        sortingMode: Long,
        sortingDirection: Long,
        displayMode: Long,
    ): Boolean {
        return mangaRepository.update(
            MangaUpdate(
                id = mangaId,
                chapterFlags = 0L.setFlag(unreadFilter, Manga.CHAPTER_UNREAD_MASK)
                    .setFlag(downloadedFilter, Manga.CHAPTER_DOWNLOADED_MASK)
                    .setFlag(bookmarkedFilter, Manga.CHAPTER_BOOKMARKED_MASK)
                    .setFlag(sortingMode, Manga.CHAPTER_SORTING_MASK)
                    .setFlag(sortingDirection, Manga.CHAPTER_SORT_DIR_MASK)
                    .setFlag(displayMode, Manga.CHAPTER_DISPLAY_MASK),
            ),
        )
    }

    private fun Long.setFlag(flag: Long, mask: Long): Long {
        return this and mask.inv() or (flag and mask)
    }
}
