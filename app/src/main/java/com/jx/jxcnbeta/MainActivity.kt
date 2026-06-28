package com.jx.jxcnbeta

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.jx.jxcnbeta.ui.theme.JxCnbetaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JxCnbetaTheme {
                CnbetaApp()
            }
        }
    }
}

data class NewsUiState(
    val items: List<NewsItem> = emptyList(),
    val nextPage: Int = 1,
    val isInitialLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = true,
    val error: String? = null,
    val readIds: Set<String> = emptySet(),
    val hiddenIds: Set<String> = emptySet(),
)

data class ArticleUiState(
    val item: NewsItem,
    val article: NewsArticle? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

class CnbetaViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = CnbetaRepository(application.applicationContext)
    private val stateStore = CnbetaStateStore(application.applicationContext)

    private val _newsState = MutableStateFlow(NewsUiState())
    val newsState: StateFlow<NewsUiState> = _newsState

    private val _articleState = MutableStateFlow<ArticleUiState?>(null)
    val articleState: StateFlow<ArticleUiState?> = _articleState

    init {
        observePersistedState()
        reload()
    }

    private fun observePersistedState() {
        viewModelScope.launch {
            stateStore.stateFlow.collect { persisted ->
                _newsState.update {
                    it.copy(
                        readIds = persisted.readIds,
                        hiddenIds = persisted.hiddenIds,
                    )
                }
            }
        }
    }

    fun reload() {
        viewModelScope.launch {
            val current = _newsState.value
            _newsState.value = current.copy(
                items = emptyList(),
                nextPage = 1,
                isInitialLoading = true,
                isLoadingMore = false,
                canLoadMore = true,
                error = null,
            )

            runCatching { repository.fetchLatest(1) }
                .onSuccess { items ->
                    _newsState.value = _newsState.value.copy(
                        items = items,
                        nextPage = 2,
                        isInitialLoading = false,
                        canLoadMore = items.isNotEmpty(),
                        error = null,
                    )
                }
                .onFailure { throwable ->
                    _newsState.value = _newsState.value.copy(
                        isInitialLoading = false,
                        error = throwable.readableMessage(),
                    )
                }
        }
    }

    fun loadMore() {
        val current = _newsState.value
        if (current.isInitialLoading || current.isLoadingMore || !current.canLoadMore) return

        viewModelScope.launch {
            _newsState.update { it.copy(isLoadingMore = true, error = null) }
            val page = current.nextPage
            runCatching { repository.fetchLatest(page) }
                .onSuccess { newItems ->
                    val knownIds = _newsState.value.items.map { it.id }.toSet()
                    val mergedItems = _newsState.value.items + newItems.filterNot { it.id in knownIds }
                    _newsState.update {
                        it.copy(
                            items = mergedItems,
                            nextPage = page + 1,
                            isLoadingMore = false,
                            canLoadMore = newItems.isNotEmpty(),
                        )
                    }
                }
                .onFailure { throwable ->
                    _newsState.update {
                        it.copy(
                            isLoadingMore = false,
                            error = throwable.readableMessage(),
                        )
                    }
                }
        }
    }

    fun openArticle(item: NewsItem) {
        markRead(item.id, read = true)
        viewModelScope.launch {
            val cachedArticle = repository.getCachedArticle(item.id)
            _articleState.value = ArticleUiState(
                item = item,
                article = cachedArticle,
                isLoading = cachedArticle == null,
            )

            runCatching { repository.refreshArticle(item) }
                .onSuccess { article ->
                    _articleState.value = ArticleUiState(
                        item = item,
                        article = article,
                        isLoading = false,
                    )
                }
                .onFailure { throwable ->
                    if (cachedArticle == null) {
                        _articleState.value = ArticleUiState(
                            item = item,
                            isLoading = false,
                            error = throwable.readableMessage(),
                        )
                    } else {
                        _articleState.value = ArticleUiState(
                            item = item,
                            article = cachedArticle,
                            isLoading = false,
                        )
                    }
                }
        }
    }

    fun closeArticle() {
        _articleState.value = null
    }

    fun markRead(id: String, read: Boolean) {
        _newsState.update { state ->
            state.copy(
                readIds = if (read) state.readIds + id else state.readIds - id,
            )
        }
        viewModelScope.launch {
            stateStore.setRead(id, read)
        }
    }

    fun deleteNews(id: String) {
        _newsState.update { state ->
            state.copy(hiddenIds = state.hiddenIds + id)
        }
        viewModelScope.launch {
            stateStore.setHidden(id, hidden = true)
        }
    }

    private fun Throwable.readableMessage(): String =
        localizedMessage?.takeIf { it.isNotBlank() } ?: "Network request failed"
}

@Composable
fun CnbetaApp(viewModel: CnbetaViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val newsState by viewModel.newsState.collectAsState()
    val articleState by viewModel.articleState.collectAsState()

    BackHandler(enabled = articleState != null) {
        viewModel.closeArticle()
    }

    Scaffold(
        topBar = {
            NewsTopBar(
                isArticleOpen = articleState != null,
                onBack = viewModel::closeArticle,
                onRefresh = viewModel::reload,
            )
        },
    ) { innerPadding ->
        if (articleState != null) {
            ArticleScreen(
                state = articleState!!,
                contentPadding = innerPadding,
                onRetry = { viewModel.openArticle(articleState!!.item) },
            )
        } else {
            NewsListScreen(
                state = newsState,
                contentPadding = innerPadding,
                onRefresh = viewModel::reload,
                onLoadMore = viewModel::loadMore,
                onOpenArticle = viewModel::openArticle,
                onToggleRead = { item, read -> viewModel.markRead(item.id, read) },
                onDeleteNews = { item -> viewModel.deleteNews(item.id) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewsTopBar(
    isArticleOpen: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = if (isArticleOpen) "Article" else "cnBeta + PConline",
                fontWeight = FontWeight.SemiBold,
            )
        },
        navigationIcon = {
            if (isArticleOpen) {
                TextButton(onClick = onBack) {
                    Text("Back")
                }
            }
        },
        actions = {
            if (!isArticleOpen) {
                TextButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewsListScreen(
    state: NewsUiState,
    contentPadding: PaddingValues,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenArticle: (NewsItem) -> Unit,
    onToggleRead: (NewsItem, Boolean) -> Unit,
    onDeleteNews: (NewsItem) -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(listState, state.items.size, state.canLoadMore) {
        snapshotFlow { listState.isNearBottom() }
            .distinctUntilChanged()
            .filter { it }
            .collect { onLoadMore() }
    }

    PullToRefreshBox(
        isRefreshing = state.isInitialLoading || state.isLoadingMore,
        onRefresh = {
            if (state.items.isEmpty()) onRefresh() else onLoadMore()
        },
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        when {
            state.isInitialLoading && state.items.isEmpty() -> LoadingPane()
            state.items.isEmpty() && state.error != null -> ErrorPane(
                message = state.error,
                action = "Retry",
                onAction = onRefresh,
            )

            else -> NewsList(
                state = state,
                listState = listState,
                onLoadMore = onLoadMore,
                onOpenArticle = onOpenArticle,
                onToggleRead = onToggleRead,
                onDeleteNews = onDeleteNews,
            )
        }
    }
}

@Composable
private fun NewsList(
    state: NewsUiState,
    listState: LazyListState,
    onLoadMore: () -> Unit,
    onOpenArticle: (NewsItem) -> Unit,
    onToggleRead: (NewsItem, Boolean) -> Unit,
    onDeleteNews: (NewsItem) -> Unit,
) {
    val visibleItems = state.items.filterNot { it.id in state.hiddenIds }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            items = visibleItems,
            key = { it.id },
        ) { item ->
            NewsItemCard(
                item = item,
                isRead = item.id in state.readIds,
                onClick = { onOpenArticle(item) },
                onToggleRead = { read -> onToggleRead(item, read) },
                onDelete = { onDeleteNews(item) },
            )
        }

        item {
            when {
                state.isLoadingMore -> LoadingMoreRow()
                state.error != null -> RetryMoreRow(state.error, onLoadMore)
                state.canLoadMore -> TextButton(
                    onClick = onLoadMore,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Load more")
                }
            }
        }
    }
}

@Composable
private fun NewsItemCard(
    item: NewsItem,
    isRead: Boolean,
    onClick: () -> Unit,
    onToggleRead: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onToggleRead(!isRead)
                SwipeToDismissBoxValue.EndToStart -> onDelete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            value == SwipeToDismissBoxValue.EndToStart
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.fillMaxWidth(),
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            SwipeBackground(
                state = dismissState,
                isRead = isRead,
            )
        },
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isRead) {
                    MaterialTheme.colorScheme.surfaceContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NewsThumbnail(item)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (isRead) FontWeight.Normal else FontWeight.SemiBold,
                            color = if (isRead) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isRead) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFFD7F5DC),
                                contentColor = Color(0xFF176B2C),
                            ) {
                                Text(
                                    text = "READ",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = listOf(item.time, item.views.takeIf { it.isNotBlank() }?.let { "$it views" })
                                .filterNotNull()
                                .filter { it.isNotBlank() }
                                .joinToString("  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        NewsSourceTag(item.source)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsSourceTag(source: NewsSource) {
    val containerColor = when (source) {
        NewsSource.CN_BETA -> MaterialTheme.colorScheme.primaryContainer
        NewsSource.PCONLINE -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = when (source) {
        NewsSource.CN_BETA -> MaterialTheme.colorScheme.onPrimaryContainer
        NewsSource.PCONLINE -> MaterialTheme.colorScheme.onTertiaryContainer
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = source.label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SwipeBackground(
    state: SwipeToDismissBoxState,
    isRead: Boolean,
) {
    val direction = state.dismissDirection
    val backgroundColor = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.tertiaryContainer
        SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
        SwipeToDismissBoxValue.Settled -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val label = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> if (isRead) "MARK UNREAD" else "MARK READ"
        SwipeToDismissBoxValue.EndToStart -> "DELETE"
        SwipeToDismissBoxValue.Settled -> ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .padding(horizontal = 18.dp),
        contentAlignment = when (direction) {
            SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
            SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
            SwipeToDismissBoxValue.Settled -> Alignment.Center
        },
    ) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = when (direction) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.onErrorContainer
                    else -> MaterialTheme.colorScheme.onTertiaryContainer
                },
            )
        }
    }
}

@Composable
private fun NewsThumbnail(item: NewsItem) {
    Box(
        modifier = Modifier
            .size(width = 104.dp, height = 76.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (item.thumbnailUrl.isNotBlank()) {
            AsyncImage(
                model = item.thumbnailUrl,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = item.source.shortLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ArticleScreen(
    state: ArticleUiState,
    contentPadding: PaddingValues,
    onRetry: () -> Unit,
) {
    when {
        state.isLoading -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        state.error != null -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            ErrorPane(
                message = state.error,
                action = "Retry",
                onAction = onRetry,
            )
        }

        state.article != null -> ArticleContent(
            article = state.article,
            contentPadding = contentPadding,
        )
    }
}

@Composable
private fun ArticleContent(
    article: NewsArticle,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                text = article.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        item {
            val byline = listOf(article.source, article.publishedAt)
                .filter { it.isNotBlank() }
                .joinToString("  ")
            if (byline.isNotBlank()) {
                Text(
                    text = byline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (article.summary.isNotBlank()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = article.summary,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        items(article.blocks) { block ->
            when (block) {
                is ArticleBlock.Paragraph -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                is ArticleBlock.Image -> ArticleImage(block)
            }
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ArticleImage(block: ArticleBlock.Image) {
    AsyncImage(
        model = block.url,
        contentDescription = block.description.ifBlank { null },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentScale = ContentScale.FillWidth,
    )
}

@Composable
private fun LoadingPane() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorPane(
    message: String,
    action: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onAction) {
            Text(action)
        }
    }
}

@Composable
private fun LoadingMoreRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.size(10.dp))
        Text("Loading")
    }
}

@Composable
private fun RetryMoreRow(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalDivider()
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = onRetry) {
            Text("Retry")
        }
    }
}

private fun LazyListState.isNearBottom(): Boolean {
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return false
    return lastVisible >= layoutInfo.totalItemsCount - 6
}

@Preview(showBackground = true)
@Composable
private fun NewsItemPreview() {
    JxCnbetaTheme {
        NewsItemCard(
            item = NewsItem(
                id = "1",
                title = "OpenAI talent departures intensify",
                url = "",
                thumbnailUrl = "",
                time = "20 min ago",
                views = "17",
                source = NewsSource.PCONLINE,
            ),
            isRead = true,
            onClick = {},
            onToggleRead = {},
            onDelete = {},
        )
    }
}
