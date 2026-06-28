package com.jx.jxcnbeta

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

private val Context.dataStore by preferencesDataStore(name = "cnbeta_state")

data class PersistedNewsState(
    val readIds: Set<String> = emptySet(),
    val hiddenIds: Set<String> = emptySet(),
)

class CnbetaStateStore(
    private val context: Context,
) {
    val stateFlow: Flow<PersistedNewsState> = context.dataStore.data.map { preferences ->
        PersistedNewsState(
            readIds = preferences[READ_IDS].orEmpty(),
            hiddenIds = preferences[HIDDEN_IDS].orEmpty(),
        )
    }

    suspend fun setRead(id: String, read: Boolean) {
        context.dataStore.edit { preferences ->
            val updated = preferences[READ_IDS].orEmpty().toMutableSet()
            if (read) updated += id else updated -= id
            preferences[READ_IDS] = updated
        }
    }

    suspend fun setHidden(id: String, hidden: Boolean) {
        context.dataStore.edit { preferences ->
            val updated = preferences[HIDDEN_IDS].orEmpty().toMutableSet()
            if (hidden) updated += id else updated -= id
            preferences[HIDDEN_IDS] = updated
        }
    }

    private companion object {
        val READ_IDS = stringSetPreferencesKey("read_ids")
        val HIDDEN_IDS = stringSetPreferencesKey("hidden_ids")
    }
}

class CnbetaArticleCache(
    private val context: Context,
) {
    suspend fun read(id: String): NewsArticle? = withContext(Dispatchers.IO) {
        val file = articleFile(id)
        if (!file.exists()) return@withContext null

        runCatching {
            val json = JSONObject(file.readText(StandardCharsets.UTF_8))
            NewsArticle(
                id = json.getString("id"),
                title = json.getString("title"),
                source = json.optString("source"),
                publishedAt = json.optString("publishedAt"),
                summary = json.optString("summary"),
                blocks = json.optJSONArray("blocks").toBlocks(),
            )
        }.getOrNull()
    }

    suspend fun write(article: NewsArticle) = withContext(Dispatchers.IO) {
        val file = articleFile(article.id)
        file.parentFile?.mkdirs()
        file.writeText(article.toJson().toString(), StandardCharsets.UTF_8)
    }

    private fun articleFile(id: String): File = File(File(context.filesDir, "article_cache"), "$id.json")

    private fun JSONArray?.toBlocks(): List<ArticleBlock> {
        if (this == null) return emptyList()

        return buildList(length()) {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                when (item.optString("type")) {
                    "paragraph" -> add(ArticleBlock.Paragraph(item.optString("text")))
                    "image" -> add(
                        ArticleBlock.Image(
                            url = item.optString("url"),
                            description = item.optString("description"),
                        ),
                    )
                }
            }
        }
    }

    private fun NewsArticle.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("source", source)
        put("publishedAt", publishedAt)
        put("summary", summary)
        put(
            "blocks",
            JSONArray().apply {
                blocks.forEach { block ->
                    put(
                        when (block) {
                            is ArticleBlock.Paragraph -> JSONObject().apply {
                                put("type", "paragraph")
                                put("text", block.text)
                            }

                            is ArticleBlock.Image -> JSONObject().apply {
                                put("type", "image")
                                put("url", block.url)
                                put("description", block.description)
                            }
                        },
                    )
                }
            },
        )
    }
}
