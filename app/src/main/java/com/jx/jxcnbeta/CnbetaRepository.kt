package com.jx.jxcnbeta

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.nio.charset.Charset

enum class NewsSource(
    val label: String,
    val shortLabel: String,
) {
    CN_BETA(label = "cnBeta", shortLabel = "CB"),
    PCONLINE_FLASH(label = "PConline快报", shortLabel = "PC"),
    PCONLINE_NEWS(label = "PConline资讯", shortLabel = "PC"),
}

data class NewsItem(
    val id: String,
    val title: String,
    val url: String,
    val thumbnailUrl: String,
    val time: String,
    val views: String,
    val source: NewsSource = NewsSource.CN_BETA,
)

data class NewsArticle(
    val id: String,
    val title: String,
    val source: String,
    val publishedAt: String,
    val summary: String,
    val blocks: List<ArticleBlock>,
)

sealed interface ArticleBlock {
    data class Paragraph(val text: String) : ArticleBlock
    data class Image(val url: String, val description: String) : ArticleBlock
}

data class ArticleComment(
    val author: String,
    val publishedAt: String,
    val content: String,
    val upVotes: String,
    val downVotes: String,
)

class CnbetaRepository(
    context: Context,
) {
    private val articleCache = CnbetaArticleCache(context.applicationContext)

    suspend fun fetchLatest(page: Int): List<NewsItem> = withContext(Dispatchers.IO) {
        val safePage = page.coerceAtLeast(1)
        val cnbetaResult = runCatching { fetchCnbetaLatest(safePage) }
        val pconlineFlashResult = runCatching { fetchPconlineFlash(safePage) }
        val pconlineNewsResult = runCatching { fetchPconlineNews(safePage) }

        if (cnbetaResult.isFailure && pconlineFlashResult.isFailure && pconlineNewsResult.isFailure) {
            throw IOException(
                "cnBeta: ${cnbetaResult.exceptionOrNull()?.message}; " +
                    "PConline快报: ${pconlineFlashResult.exceptionOrNull()?.message}; " +
                    "PConline资讯: ${pconlineNewsResult.exceptionOrNull()?.message}",
            )
        }

        interleave(
            cnbetaItems = cnbetaResult.getOrDefault(emptyList()),
            pconlineFlashItems = pconlineFlashResult.getOrDefault(emptyList()),
            pconlineNewsItems = pconlineNewsResult.getOrDefault(emptyList()),
        )
    }

    private fun fetchCnbetaLatest(page: Int): List<NewsItem> {
        val doc = getDocument(
            url = "$CNBETA_BASE_URL/list/latest_$page.htm",
            referrer = "$CNBETA_BASE_URL/",
        )
        return doc.select("ul.info_list > li").mapNotNull { item ->
            val link = item.selectFirst("p.txt_thumb a[href], div.txt_area a[href]") ?: return@mapNotNull null
            val href = link.attr("href")
            val id = viewIdRegex.find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = item.selectFirst("p.txt_detail")?.text()
                ?: item.selectFirst("p.txt_thumb img")?.attr("alt")
                ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null

            val thumbnailUrl = item.selectFirst("p.txt_thumb img[src]")?.absUrl("src").orEmpty()
            val time = item.selectFirst("p.txt_time .ico_time")?.text().orEmpty()
            val views = item.selectFirst("p.txt_time .ico_view")?.text().orEmpty()

            NewsItem(
                id = id,
                title = title,
                url = absoluteCnbetaUrl(href),
                thumbnailUrl = thumbnailUrl,
                time = time,
                views = views,
                source = NewsSource.CN_BETA,
            )
        }
    }

    private fun fetchPconlineFlash(page: Int): List<NewsItem> {
        if (page == 1) {
            val doc = getDocument(
                url = PCONLINE_MOBILE_LIST_URL,
                referrer = "$PCONLINE_BASE_URL/",
            )
            return doc.select("#JnewsList li.hour").mapNotNull { item ->
                val link = item.selectFirst("a.dl[href]") ?: return@mapNotNull null
                val href = link.attr("href")
                val remoteId = pconlineIdRegex.find(href)?.groupValues?.get(1) ?: return@mapNotNull null
                val title = link.attr("title").ifBlank {
                    link.selectFirst("dt")?.text().orEmpty()
                }
                if (title.isBlank()) return@mapNotNull null

                val timeElement = item.selectFirst(".u-pubTime")
                NewsItem(
                    id = pconlineItemId(remoteId),
                    title = title,
                    url = absolutePconlineUrl(href),
                    thumbnailUrl = "",
                    time = formatPconlineDate(timeElement?.attr("data-date").orEmpty())
                        .ifBlank { timeElement?.text().orEmpty() },
                    views = "",
                    source = NewsSource.PCONLINE_FLASH,
                )
            }
        }

        val body = getText(
            url = "$PCONLINE_PAGE_URL_PREFIX${page - 1}.html",
            referrer = PCONLINE_MOBILE_LIST_URL,
        )
        return pconlineArticleRegex.findAll(body).mapNotNull { match ->
            val article = match.value
            val remoteId = article.javascriptField("id")
            val title = article.javascriptField("title")
            val href = article.javascriptField("url")
            if (remoteId.isBlank() || title.isBlank() || href.isBlank()) {
                return@mapNotNull null
            }

            NewsItem(
                id = pconlineItemId(remoteId),
                title = title,
                url = absolutePconlineUrl(href),
                thumbnailUrl = article.javascriptField("cover")
                    .takeUnless { it.endsWith("/blank.jpg") }
                    .orEmpty(),
                time = article.javascriptField("pc_pubDate"),
                views = "",
                source = NewsSource.PCONLINE_FLASH,
            )
        }.toList()
    }

    private fun fetchPconlineNews(page: Int): List<NewsItem> {
        val url = if (page == 1) {
            PCONLINE_NEWS_LIST_URL
        } else {
            "${PCONLINE_NEWS_LIST_URL}index_${page - 1}.html"
        }
        val doc = getDocument(
            url = url,
            referrer = PCONLINE_NEWS_LIST_URL,
            charset = PCONLINE_DESKTOP_CHARSET,
        )

        return doc.select("div.list-wrap li").mapNotNull { item ->
            val link = item.selectFirst("dl dt a[href]") ?: return@mapNotNull null
            val href = link.attr("href")
            val remoteId = pconlineIdRegex.find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = link.text().trim()
            if (title.isBlank()) return@mapNotNull null

            val image = item.selectFirst("i.lpic img")
            val thumbnailUrl = image?.attr("#src")
                ?.takeIf { it.isNotBlank() }
                ?.let(::absolutePconlineUrl)
                ?: image?.absUrl("src")
                    ?.takeUnless { it.contains("/blank.") }
                    .orEmpty()
            val time = item.selectFirst(".date")?.text()
                ?.trim()
                ?.trimStart('|')
                ?.trim()
                .orEmpty()

            NewsItem(
                id = pconlineItemId(remoteId),
                title = title,
                url = absolutePconlineUrl(href),
                thumbnailUrl = thumbnailUrl,
                time = time,
                views = "",
                source = NewsSource.PCONLINE_NEWS,
            )
        }.take(PCONLINE_NEWS_PAGE_SIZE)
    }

    suspend fun getCachedArticle(id: String): NewsArticle? = articleCache.read(id)

    suspend fun refreshArticle(item: NewsItem): NewsArticle = withContext(Dispatchers.IO) {
        when (item.source) {
            NewsSource.CN_BETA -> refreshCnbetaArticle(item)
            NewsSource.PCONLINE_FLASH,
            NewsSource.PCONLINE_NEWS,
            -> refreshPconlineArticle(item)
        }
    }

    suspend fun fetchComments(item: NewsItem): List<ArticleComment> = withContext(Dispatchers.IO) {
        when (item.source) {
            NewsSource.CN_BETA -> fetchCnbetaComments(item.id)
            NewsSource.PCONLINE_FLASH,
            NewsSource.PCONLINE_NEWS,
            -> emptyList()
        }
    }

    private suspend fun refreshCnbetaArticle(item: NewsItem): NewsArticle {
        val remoteId = item.id
        val doc = getDocument(
            url = "$CNBETA_BASE_URL/view/$remoteId.htm",
            referrer = "$CNBETA_BASE_URL/list/latest_1.htm",
        )
        val title = doc.selectFirst("h1.article-tit")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")
            ?: "cnBeta"
        val source = doc.selectFirst(".article-byline span span")?.text()
            ?: doc.selectFirst(".article-byline span")?.text()
            ?: ""
        val publishedAt = doc.selectFirst(".article-byline time")?.text().orEmpty()
        val summary = doc.selectFirst(".article-summ p")?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
            ?: ""
        val blocks = doc.selectFirst("#artibody")?.toArticleBlocks().orEmpty()

        val article = NewsArticle(
            id = item.id,
            title = title,
            source = source,
            publishedAt = publishedAt,
            summary = summary,
            blocks = blocks,
        )
        articleCache.write(article)
        return article
    }

    private suspend fun refreshPconlineArticle(item: NewsItem): NewsArticle {
        val mobileUrl = item.url.replace(
            oldValue = "https://news.pconline.com.cn/",
            newValue = PCONLINE_MOBILE_ARTICLE_URL_PREFIX,
        )
        val doc = getDocument(
            url = mobileUrl,
            referrer = PCONLINE_MOBILE_LIST_URL,
        )
        val title = doc.selectFirst("h1.art-title")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: item.title
        val author = doc.selectFirst(".artAuthorInfo .author .name")?.text()
            ?: doc.selectFirst("meta[property=article:author]")?.attr("content")
            ?: "PConline"
        val publishedAt = doc.selectFirst(".artAuthorInfo .pubTime")?.text()
            ?: doc.selectFirst(".art-info .date")?.text()
            ?: item.time
        val summary = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()
        val blocks = doc.selectFirst(".art-content")?.toArticleBlocks().orEmpty()

        val article = NewsArticle(
            id = item.id,
            title = title,
            source = author,
            publishedAt = publishedAt,
            summary = summary,
            blocks = blocks,
        )
        articleCache.write(article)
        return article
    }

    private fun fetchCnbetaComments(remoteId: String): List<ArticleComment> {
        val doc = getDocument(
            url = "$CNBETA_BASE_URL/comment/$remoteId.htm",
            referrer = "$CNBETA_BASE_URL/view/$remoteId.htm",
        )

        return doc.select("#J_commt_list > li").mapNotNull { item ->
            val content = item.selectFirst(".con")?.text()?.trim().orEmpty()
            if (content.isBlank()) return@mapNotNull null

            val votes = item.select(".tools span")
            ArticleComment(
                author = item.selectFirst(".userName")?.text()?.trim().orEmpty()
                    .ifBlank { "匿名人士" },
                publishedAt = item.selectFirst(".title .time")?.text()?.trim().orEmpty(),
                content = content,
                upVotes = votes.getOrNull(0)?.text()?.trim().orEmpty(),
                downVotes = votes.getOrNull(1)?.text()?.trim().orEmpty(),
            )
        }.take(CNBETA_COMMENT_LIMIT)
    }

    private fun getDocument(
        url: String,
        referrer: String,
        charset: Charset? = null,
    ): Document = Jsoup.parse(getText(url, referrer, charset), url)

    private fun getText(
        url: String,
        referrer: String,
        charset: Charset? = null,
    ): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", ACCEPT)
            .header("Accept-Language", ACCEPT_LANGUAGE)
            .header("Cache-Control", "no-cache, no-store, max-age=0")
            .header("Pragma", "no-cache")
            .header("Referer", referrer)
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-User", "?1")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }

            val body = response.body ?: throw IOException("Empty response")
            val html = if (charset == null) {
                body.string()
            } else {
                body.bytes().toString(charset)
            }
            if (html.isBlank()) {
                throw IOException("Empty response")
            }

            return html
        }
    }

    private fun Element.toArticleBlocks(): List<ArticleBlock> {
        val blocks = mutableListOf<ArticleBlock>()
        children().forEach { child ->
            when (child.tagName().lowercase()) {
                "p" -> {
                    val images = child.select("img")
                    if (images.isNotEmpty()) {
                        images.forEach { image ->
                            val imageUrl = image.resolvedImageUrl()
                            if (imageUrl.isNotBlank()) {
                                blocks += ArticleBlock.Image(
                                    url = imageUrl,
                                    description = image.attr("alt"),
                                )
                            }
                        }
                    }

                    val text = child.text().trim()
                    if (text.isNotBlank() && images.isEmpty()) {
                        blocks += ArticleBlock.Paragraph(text)
                    }
                }

                "img" -> {
                    val imageUrl = child.resolvedImageUrl()
                    if (imageUrl.isNotBlank()) {
                        blocks += ArticleBlock.Image(
                            url = imageUrl,
                            description = child.attr("alt"),
                        )
                    }
                }
            }
        }
        return blocks
    }

    private fun Element.resolvedImageUrl(): String {
        val lazyUrl = attr("#src").ifBlank { attr("data-src") }
        return if (lazyUrl.isNotBlank()) {
            when {
                lazyUrl.startsWith("//") -> "https:$lazyUrl"
                lazyUrl.startsWith("http://") || lazyUrl.startsWith("https://") -> lazyUrl
                else -> absUrl(if (hasAttr("#src")) "#src" else "data-src")
            }
        } else {
            absUrl("src").takeUnless { it.contains("/blank.") }.orEmpty()
        }
    }

    private fun absoluteCnbetaUrl(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("//") -> "https:$path"
        path.startsWith("/") -> "$CNBETA_BASE_URL$path"
        else -> "$CNBETA_BASE_URL/$path"
    }

    private fun absolutePconlineUrl(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("//") -> "https:$path"
        path.startsWith("/") -> "$PCONLINE_BASE_URL$path"
        else -> "$PCONLINE_BASE_URL/$path"
    }

    private fun interleave(
        cnbetaItems: List<NewsItem>,
        pconlineFlashItems: List<NewsItem>,
        pconlineNewsItems: List<NewsItem>,
    ): List<NewsItem> {
        val newsIds = pconlineNewsItems.mapTo(mutableSetOf()) { it.id }
        val uniqueFlashItems = pconlineFlashItems.filterNot { it.id in newsIds }
        return buildList(cnbetaItems.size + uniqueFlashItems.size + pconlineNewsItems.size) {
            val maxSize = maxOf(cnbetaItems.size, uniqueFlashItems.size, pconlineNewsItems.size)
            repeat(maxSize) { index ->
                cnbetaItems.getOrNull(index)?.let(::add)
                uniqueFlashItems.getOrNull(index)?.let(::add)
                pconlineNewsItems.getOrNull(index)?.let(::add)
            }
        }
    }

    private fun pconlineItemId(remoteId: String): String = "$PCONLINE_ID_PREFIX$remoteId"

    private fun formatPconlineDate(value: String): String {
        val parts = value.split(",")
        if (parts.size < 5) return ""
        return "${parts[0]}-${parts[1]}-${parts[2]} ${parts[3]}:${parts[4]}"
    }

    private fun String.javascriptField(name: String): String {
        val fieldRegex = Regex(
            """"${Regex.escape(name)}"\s*:\s*"((?:\\.|[^"\\])*)"""",
        )
        return fieldRegex.find(this)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\/", "/")
            ?.replace("\\\\", "\\")
            .orEmpty()
    }

    private companion object {
        const val CNBETA_BASE_URL = "https://m.cnbeta.com.tw"
        const val PCONLINE_BASE_URL = "https://www.pconline.com.cn"
        const val PCONLINE_MOBILE_LIST_URL = "https://g.pconline.com.cn/x/news/"
        const val PCONLINE_PAGE_URL_PREFIX = "$PCONLINE_BASE_URL/3g/2011/news/index_"
        const val PCONLINE_NEWS_LIST_URL = "https://news.pconline.com.cn/it/"
        const val PCONLINE_MOBILE_ARTICLE_URL_PREFIX = "https://g.pconline.com.cn/x/"
        const val PCONLINE_ID_PREFIX = "pconline:"
        const val PCONLINE_NEWS_PAGE_SIZE = 20
        const val CNBETA_COMMENT_LIMIT = 20
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
        const val ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        const val ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7"
        val viewIdRegex = Regex("""/view/(\d+)\.htm""")
        val pconlineIdRegex = Regex("""/(\d{6,})\.html""")
        val pconlineArticleRegex = Regex(
            """\{\s*"channelName".*?\n\},?""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val PCONLINE_DESKTOP_CHARSET: Charset = Charset.forName("GB18030")
        val httpClient: OkHttpClient by lazy {
            val cookieManager = CookieManager().apply {
                setCookiePolicy(CookiePolicy.ACCEPT_ALL)
            }
            OkHttpClient.Builder()
                .cookieJar(JavaNetCookieJar(cookieManager))
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}
