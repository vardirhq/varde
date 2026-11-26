package no.vardir.varde

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute

private enum class Tab(val label: String, val icon: ImageVector, val route: Any) {
    Inbox("Inbox", Icons.Default.Notifications, InboxRoute),
    Products("Products", Icons.Default.Star, ProductsRoute),
    Repos("Repos", Icons.AutoMirrored.Filled.List, ReposRoute),
    Settings("Settings", Icons.Default.Settings, SettingsRoute),
}

@Composable
fun Varde(vm: AppViewModel = viewModel()) {
    if (!vm.signedIn) { SignInScreen(vm); return }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val currentTab = Tab.entries.firstOrNull { tab -> destination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (currentTab != null) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(selected = tab == currentTab, onClick = { nav.switchTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) }, label = { Text(tab.label) })
                }
            }
        }
    ) { padding ->
        VardeNavHost(vm, nav, Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding))
    }
}

private fun NavHostController.switchTab(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun VardeNavHost(vm: AppViewModel, nav: NavHostController, modifier: Modifier) {
    val repositories = vm.repositories
    val portfolio = vm.portfolio
    val uri = LocalUriHandler.current
    val openRepo: (Repo, String) -> Unit = { repo, page -> nav.navigate(RepositoryRoute(repo.id, page)) }
    val openAttention: (AttentionItem) -> Unit = { item ->
        val route = attentionRoute(item)
        val known = repositories.included.any { it.id == item.repositoryId }
        if (route != null && known) nav.navigate(RepositoryRoute(item.repositoryId, route))
        else runCatching { uri.openUri(item.url) }
    }
    NavHost(nav, startDestination = InboxRoute, modifier = modifier) {
        composable<InboxRoute> {
            InboxScreen(vm, vm.user, vm.inbox, repositories, portfolio, vm.delegations, onDelegationsChanged = vm::refreshDelegations,
                onOpenItem = openAttention, onOpenPull = { repoId, number -> nav.navigate(RepositoryRoute(repoId, "pull:$number")) }, onOpenProduct = { nav.navigate(ProductRoute(it.id)) },
                onShowProducts = { nav.switchTab(ProductsRoute) })
        }
        composable<ProductsRoute> {
            ProductsScreen(portfolio, onOpen = { nav.navigate(ProductRoute(it.id)) },
                onCreate = { nav.navigate(ProductEditRoute(java.util.UUID.randomUUID().toString(), isNew = true)) })
        }
        composable<ReposRoute> { ReposScreen(vm, repositories, onOpen = { openRepo(it, "overview") }) }
        composable<SettingsRoute> {
            SettingsScreen(vm, vm.user, onSignOut = vm::logout, repositories, portfolio, onManageRepos = { nav.navigate(RepoVisibilityRoute) })
        }
        composable<RepoVisibilityRoute> { RepoVisibilityScreen(repositories, onBack = { nav.popBackStack() }) }
        composable<ProductRoute> { backStack ->
            val route = backStack.toRoute<ProductRoute>()
            ProductScreen(portfolio, repositories, vm.inbox, vm.delegations, vm.github, route.productId, onBack = { nav.popBackStack() },
                onEdit = { nav.navigate(ProductEditRoute(route.productId, isNew = false)) },
                onOpenRepo = openRepo)
        }
        composable<ProductEditRoute> { backStack ->
            val route = backStack.toRoute<ProductEditRoute>()
            val original = if (route.isNew) Product(route.productId, "") else portfolio.find(route.productId)
            if (original == null) MissingScreen("Product not found", "It may have been deleted.") { nav.popBackStack() }
            else ProductEditor(original, repositories.included, portfolio.error,
                onCancel = { nav.popBackStack() },
                onSave = { product ->
                    if (portfolio.save(product)) {
                        if (route.isNew) nav.navigate(ProductRoute(product.id)) { popUpTo<ProductEditRoute> { inclusive = true } }
                        else nav.popBackStack()
                    }
                })
        }
        composable<RepositoryRoute> { backStack ->
            val route = backStack.toRoute<RepositoryRoute>()
            val repo = repositories.find(route.repoId)
            if (repo == null) {
                if (vm.busy) Box(Modifier.fillMaxSize()) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else MissingScreen("Repository unavailable", "Refresh GitHub data or check that you still have access.") { nav.popBackStack() }
            } else RepositoryWorkspace(vm.github, vm.delegations, repo, route.page,
                onNavigate = { page -> nav.navigate(RepositoryRoute(repo.id, page)) },
                onBack = { nav.popBackStack() })
        }
    }
}

@Composable
private fun MissingScreen(title: String, message: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(title = title, onBack = onBack)
        Notice(title, message, NoticeKind.Warning, Modifier.padding(horizontal = ScreenGutter))
    }
}

/** Account-wide sync state shown at the top of each tab. */
@Composable
internal fun SyncStatus(sync: SyncInfo, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sync.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        sync.error?.let { Notice("Couldn't refresh", it, NoticeKind.Error) }
        sync.snapshotTime?.let { time ->
            Notice("Showing saved repositories", "Saved ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(time))}. Refresh when you're online.")
        }
    }
}
