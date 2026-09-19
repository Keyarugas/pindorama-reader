package eu.kanade.tachiyomi.ui.library

import tachiyomi.domain.category.model.Category

/** Presentation only: private works are not a category and have no category ID. */
sealed interface LibraryPage {
    data class Normal(val category: Category) : LibraryPage
    data object Private : LibraryPage
}
