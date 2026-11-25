package no.vardir.varde

import kotlinx.serialization.Serializable

// Type-safe Navigation Compose destinations. Tabs are objects; detail screens carry the ids they need,
// so they survive process death and can be restored from the back stack.
@Serializable internal data object InboxRoute
@Serializable internal data object ProductsRoute
@Serializable internal data object ReposRoute
@Serializable internal data object SettingsRoute
@Serializable internal data object RepoVisibilityRoute
@Serializable internal data class ProductRoute(val productId: String)
@Serializable internal data class ProductEditRoute(val productId: String, val isNew: Boolean)
/** [page] uses the workspace page vocabulary ("overview", "pull:12", "run:34", "files:src"). */
@Serializable internal data class RepositoryRoute(val repoId: Long, val page: String = "overview")
