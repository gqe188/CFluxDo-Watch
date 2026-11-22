package com.cfluxdo.watch.net

import android.util.Log
import com.cfluxdo.watch.model.CategoryItem
import com.cfluxdo.watch.model.PostItem
import com.cfluxdo.watch.model.TopicDetailData
import com.cfluxdo.watch.model.TopicListPage
import com.cfluxdo.watch.model.TopicSummary
import com.cfluxdo.watch.model.UserProfile
import com.cfluxdo.watch.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Lightweight Discourse HTTP client for linux.do.
 * Transparently routes HTTPS traffic through CFluxDo's Rust local gateway (127.0.0.1:<port>)
 * with original Host header, avoiding double-TLS overhead and preserving exact cookie scoping.
 */
class DiscourseClient(
    private val gateway: DohGatewayManager,
    val cookieStore: PersistentCookieStore,
    private val prefs: Prefs
) {
    companion object {
        const val BASE_URL = "https://linux.do"
        const val BASE_HOST = "linux.do"
        private const val TAG = "DiscourseClient"
    }

    @Volatile
    var csrfToken: String? = null
        private set

    fun getUserAgent(): String {
        val cached = prefs.cachedUserAgent
        if (!cached.isNullOrBlank()) return cached
        return ClientHints.defaultUserAgent()
    }

    fun updateUserAgent(rawUa: String) {
        val sanitized = ClientHints.sanitizeUserAgent(rawUa)
        prefs.cachedUserAgent = sanitized
    }

    private val gatewayAndCookieInterceptor = Interceptor { chain ->
        val origRequest = chain.request()
        val origUrl = origRequest.url
        val ua = getUserAgent()
        val isXhr = origRequest.header("X-Requested-With") == "XMLHttpRequest"

        val builder = origRequest.newBuilder()
            .header("User-Agent", ua)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")

        for ((k, v) in ClientHints.headersFor(ua)) {
            builder.header(k, v)
        }

        if (isXhr) {
            val m = origRequest.method.uppercase()
            if (m != "GET" && m != "HEAD") {
                builder.header("Origin", BASE_URL)
            }
            builder.header("Referer", "$BASE_URL/")
            builder.header("Sec-Fetch-Dest", "empty")
            builder.header("Sec-Fetch-Mode", "cors")
            builder.header("Sec-Fetch-Site", "same-origin")
        }

        // Attach cookies strictly matched to original domain (e.g. linux.do)
        if (origRequest.header("Cookie") == null) {
            cookieStore.buildCookieHeader(origUrl)?.let {
                builder.header("Cookie", it)
            }
        }

        // Rewrite HTTPS -> plain HTTP to loopback Rust gateway (exact CFluxDo _GatewayAdapterWrapper)
        val port = gateway.activePort
        if (gateway.isRunning && port > 0 && origUrl.scheme == "https") {
            val gwUrl = origUrl.newBuilder()
                .scheme("http")
                .host("127.0.0.1")
                .port(port)
                .build()
            builder.url(gwUrl)
            builder.header("Host", origUrl.host)
        }

        val response = chain.proceed(builder.build())

        // Persist Set-Cookie headers back under the ORIGINAL URL domain
        val setCookies = response.headers("Set-Cookie")
        if (setCookies.isNotEmpty()) {
            cookieStore.saveFromHeaders(origUrl, setCookies)
        }

        response
    }

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(4, 2, TimeUnit.MINUTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(gatewayAndCookieInterceptor)
        .build()

    data class RawHttpResult(
        val code: Int,
        val body: String,
        val location: String?
    )

    suspend fun executeGetJson(path: String, query: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val urlBuilder = "$BASE_URL$path".toHttpUrl().newBuilder()
            for ((k, v) in query) {
                urlBuilder.addQueryParameter(k, v)
            }
            val req = Request.Builder()
                .url(urlBuilder.build())
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
                .get()
                .build()
            val result = executeChecked(req)
            gateway.drainAccelUpdates()
            JSONObject(result.body)
        }

    suspend fun fetchCsrfToken(): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/session/csrf")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .get()
            .build()
        val res = executeChecked(req)
        val token = JSONObject(res.body).optString("csrf")
        if (token.isBlank()) throw IOException("无法获取 CSRF Token")
        csrfToken = token
        token
    }

    suspend fun executePostForm(
        path: String,
        params: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
        useCsrf: Boolean = true
    ): RawHttpResult = withContext(Dispatchers.IO) {
        val token = if (useCsrf) {
            csrfToken?.takeIf { it.isNotBlank() } ?: fetchCsrfToken()
        } else null

        val formBuilder = FormBody.Builder()
        for ((k, v) in params) {
            formBuilder.add(k, v)
        }
        val reqBuilder = Request.Builder()
            .url("$BASE_URL$path")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(formBuilder.build())

        if (token != null) {
            reqBuilder.header("X-CSRF-Token", token)
        }
        for ((k, v) in extraHeaders) {
            reqBuilder.header(k, v)
        }

        executeChecked(reqBuilder.build(), allowRedirect = true)
    }

    suspend fun executeDelete(path: String, query: Map<String, String> = emptyMap()): RawHttpResult =
        withContext(Dispatchers.IO) {
            val token = csrfToken?.takeIf { it.isNotBlank() } ?: fetchCsrfToken()
            val urlBuilder = "$BASE_URL$path".toHttpUrl().newBuilder()
            for ((k, v) in query) urlBuilder.addQueryParameter(k, v)
            val req = Request.Builder()
                .url(urlBuilder.build())
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-CSRF-Token", token)
                .delete()
                .build()
            executeChecked(req)
        }

    suspend fun downloadBytes(rawUrl: String): ByteArray = withContext(Dispatchers.IO) {
        val fullUrl = resolveUrl(rawUrl)
        val req = Request.Builder()
            .url(fullUrl)
            .header("Accept", "image/webp,image/png,image/jpeg,image/*,*/*;q=0.8")
            .header("Referer", "$BASE_URL/")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("图片下载失败 HTTP ${resp.code}")
            }
            resp.body?.bytes() ?: throw IOException("空图片响应")
        }
    }

    private fun executeChecked(request: Request, allowRedirect: Boolean = false): RawHttpResult {
        httpClient.newCall(request).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val cfMitigated = resp.header("cf-mitigated")
            val contentType = resp.header("content-type")

            if (CfChallengeDetector.isChallenge(resp.code, cfMitigated, contentType, bodyStr)) {
                throw CfChallengeException()
            }

            if (resp.code == 403 && bodyStr.contains("BAD CSRF", ignoreCase = true)) {
                csrfToken = null
                throw IOException("CSRF 已过期，请重试")
            }

            if (resp.code in 300..399 && allowRedirect) {
                return RawHttpResult(resp.code, bodyStr, resp.header("Location"))
            }

            if (resp.code == 403 || resp.code == 401) {
                if (bodyStr.contains("login", ignoreCase = true) ||
                    bodyStr.contains("not_logged_in", ignoreCase = true) ||
                    !cookieStore.hasLoginSession()
                ) {
                    throw AuthRequiredException("需要登录查看此内容")
                }
            }

            if (!resp.isSuccessful) {
                val gwErr = resp.header("x-gateway-error")
                val msg = if (!gwErr.isNullOrBlank()) {
                    "网关连接失败: $gwErr"
                } else {
                    "请求失败 (HTTP ${resp.code})"
                }
                Log.w(TAG, "$msg url=${request.url}")
                throw IOException(msg)
            }

            return RawHttpResult(resp.code, bodyStr, resp.header("Location"))
        }
    }

    // ---------- Discourse Forum APIs ----------

    suspend fun fetchTopics(
        filter: String,
        page: Int = 0,
        categoryId: Int? = null,
        categorySlug: String? = null
    ): TopicListPage {
        val path = if (categoryId != null && !categorySlug.isNullOrBlank()) {
            "/c/$categorySlug/$categoryId/l/$filter.json"
        } else {
            "/$filter.json"
        }
        val query = mutableMapOf<String, String>()
        if (page > 0) query["page"] = page.toString()

        val root = executeGetJson(path, query)
        val listObj = root.optJSONObject("topic_list") ?: JSONObject()
        val arr = listObj.optJSONArray("topics") ?: JSONArray()
        val topics = ArrayList<TopicSummary>(arr.length())
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            topics.add(
                TopicSummary(
                    id = t.optInt("id"),
                    title = t.optString("title").ifBlank { t.optString("fancy_title") },
                    slug = t.optString("slug"),
                    postsCount = t.optInt("posts_count", 1),
                    replyCount = t.optInt("reply_count", (t.optInt("posts_count", 1) - 1).coerceAtLeast(0)),
                    views = t.optInt("views", 0),
                    likeCount = t.optInt("like_count", 0),
                    categoryId = t.optInt("category_id", 0),
                    lastPostedAt = t.optString("last_posted_at").takeIf { it.isNotBlank() && it != "null" },
                    createdAt = t.optString("created_at").takeIf { it.isNotBlank() && it != "null" },
                    pinned = t.optBoolean("pinned", false),
                    unseen = t.optBoolean("unseen", false),
                    unreadPosts = t.optInt("unread_posts", 0)
                )
            )
        }
        val moreUrl = listObj.optString("more_topics_url").takeIf { it.isNotBlank() && it != "null" }
        return TopicListPage(
            topics = topics,
            hasMore = moreUrl != null && topics.isNotEmpty(),
            moreUrl = moreUrl
        )
    }

    suspend fun fetchTopicDetail(topicId: Int, usernameFilter: String? = null): TopicDetailData {
        val query = mutableMapOf<String, String>()
        if (!usernameFilter.isNullOrBlank()) {
            query["username_filters"] = usernameFilter
        }
        val root = executeGetJson("/t/$topicId.json", query)
        val postStream = root.optJSONObject("post_stream") ?: JSONObject()
        val streamArr = postStream.optJSONArray("stream") ?: JSONArray()
        val streamIds = ArrayList<Int>(streamArr.length())
        for (i in 0 until streamArr.length()) {
            streamIds.add(streamArr.optInt(i))
        }
        val posts = parsePostsArray(postStream.optJSONArray("posts"))
        val details = root.optJSONObject("details")
        val opUsername = details?.optJSONObject("created_by")?.optString("username")
            ?.takeIf { it.isNotBlank() }
            ?: posts.firstOrNull()?.username.orEmpty()

        return TopicDetailData(
            id = root.optInt("id", topicId),
            title = root.optString("title"),
            categoryId = root.optInt("category_id", 0),
            postsCount = root.optInt("posts_count", posts.size),
            opUsername = opUsername,
            streamPostIds = streamIds,
            posts = posts
        )
    }

    suspend fun fetchPostsByIds(topicId: Int, postIds: List<Int>): List<PostItem> =
        withContext(Dispatchers.IO) {
            if (postIds.isEmpty()) return@withContext emptyList()
            val urlBuilder = "$BASE_URL/t/$topicId/posts.json".toHttpUrl().newBuilder()
            for (id in postIds) {
                urlBuilder.addQueryParameter("post_ids[]", id.toString())
            }
            val req = Request.Builder()
                .url(urlBuilder.build())
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
                .get()
                .build()
            val res = executeChecked(req)
            val root = JSONObject(res.body)
            val postStream = root.optJSONObject("post_stream")
            parsePostsArray(postStream?.optJSONArray("posts"))
        }

    private fun parsePostsArray(arr: JSONArray?): List<PostItem> {
        if (arr == null) return emptyList()
        val list = ArrayList<PostItem>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            var likes = 0
            var liked = false
            var canLike = false
            val actions = p.optJSONArray("actions_summary")
            if (actions != null) {
                for (j in 0 until actions.length()) {
                    val act = actions.optJSONObject(j) ?: continue
                    if (act.optInt("id") == 2) {
                        likes = act.optInt("count", 0)
                        liked = act.optBoolean("acted", false)
                        canLike = act.optBoolean("can_act", false) || liked
                        break
                    }
                }
            }
            val username = p.optString("username")
            val name = p.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: username
            list.add(
                PostItem(
                    id = p.optInt("id"),
                    postNumber = p.optInt("post_number"),
                    username = username,
                    displayName = name,
                    cookedHtml = p.optString("cooked"),
                    createdAt = p.optString("created_at").takeIf { it.isNotBlank() && it != "null" },
                    likeCount = likes,
                    liked = liked,
                    canLike = canLike,
                    replyToPostNumber = if (p.isNull("reply_to_post_number")) null else p.optInt("reply_to_post_number")
                )
            )
        }
        return list
    }

    suspend fun toggleLike(postId: Int, currentlyLiked: Boolean) {
        if (currentlyLiked) {
            executeDelete("/post_actions/$postId", mapOf("post_action_type_id" to "2"))
        } else {
            executePostForm(
                "/post_actions",
                mapOf("id" to postId.toString(), "post_action_type_id" to "2")
            )
        }
    }

    suspend fun fetchCategories(forceRefresh: Boolean = false): List<CategoryItem> {
        if (!forceRefresh) {
            val cached = prefs.categoriesCacheJson
            if (!cached.isNullOrBlank()) {
                val parsed = parseCategories(cached)
                if (parsed.isNotEmpty()) return parsed
            }
        }
        val root = executeGetJson("/categories.json", mapOf("include_subcategories" to "true"))
        val raw = root.toString()
        prefs.categoriesCacheJson = raw
        return parseCategories(raw)
    }

    fun getCachedCategoriesMap(): Map<Int, CategoryItem> {
        val raw = prefs.categoriesCacheJson ?: return emptyMap()
        return parseCategories(raw).associateBy { it.id }
    }

    private fun parseCategories(rawJson: String): List<CategoryItem> {
        return try {
            val root = JSONObject(rawJson)
            val list = root.optJSONObject("category_list")?.optJSONArray("categories") ?: return emptyList()
            val out = mutableListOf<CategoryItem>()
            for (i in 0 until list.length()) {
                val c = list.optJSONObject(i) ?: continue
                out.add(
                    CategoryItem(
                        id = c.optInt("id"),
                        name = c.optString("name"),
                        slug = c.optString("slug"),
                        colorHex = c.optString("color", "E0A24D"),
                        parentCategoryId = null
                    )
                )
                val subs = c.optJSONArray("subcategory_list")
                if (subs != null) {
                    for (j in 0 until subs.length()) {
                        val s = subs.optJSONObject(j) ?: continue
                        out.add(
                            CategoryItem(
                                id = s.optInt("id"),
                                name = "${c.optString("name")} / ${s.optString("name")}",
                                slug = s.optString("slug"),
                                colorHex = s.optString("color", c.optString("color", "E0A24D")),
                                parentCategoryId = c.optInt("id")
                            )
                        )
                    }
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun fetchCurrentUser(): UserProfile? {
        return try {
            val root = executeGetJson("/session/current.json")
            val u = root.optJSONObject("current_user") ?: return null
            val username = u.optString("username").takeIf { it.isNotBlank() } ?: return null
            val profile = UserProfile(
                username = username,
                name = u.optString("name").ifBlank { username },
                trustLevel = u.optInt("trust_level", 0)
            )
            prefs.cachedUsername = profile.username
            prefs.cachedTrustLevel = profile.trustLevel
            profile
        } catch (e: CfChallengeException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    fun resolveUrl(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$BASE_URL$trimmed"
            else -> "$BASE_URL/$trimmed"
        }
    }
}
