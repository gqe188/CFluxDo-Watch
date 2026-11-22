package com.cfluxdo.watch.model

data class TopicSummary(
    val id: Int,
    val title: String,
    val slug: String,
    val postsCount: Int,
    val replyCount: Int,
    val views: Int,
    val likeCount: Int,
    val categoryId: Int,
    val lastPostedAt: String?,
    val createdAt: String?,
    val pinned: Boolean = false,
    val unseen: Boolean = false,
    val unreadPosts: Int = 0
)

data class TopicListPage(
    val topics: List<TopicSummary>,
    val hasMore: Boolean,
    val moreUrl: String?
)

data class PostItem(
    val id: Int,
    val postNumber: Int,
    val username: String,
    val displayName: String,
    val cookedHtml: String,
    val createdAt: String?,
    val likeCount: Int,
    val liked: Boolean,
    val canLike: Boolean,
    val replyToPostNumber: Int?
)

data class TopicDetailData(
    val id: Int,
    val title: String,
    val categoryId: Int,
    val postsCount: Int,
    val opUsername: String,
    val streamPostIds: List<Int>,
    val posts: List<PostItem>
)

data class CategoryItem(
    val id: Int,
    val name: String,
    val slug: String,
    val colorHex: String,
    val parentCategoryId: Int? = null
)

data class UserProfile(
    val username: String,
    val name: String,
    val trustLevel: Int
)

data class GatewaySelectStatus(
    val running: Boolean,
    val port: Int,
    val mode: String,
    val phase: String,
    val winnerIp: String?,
    val latencyMs: Int?,
    val source: String?,
    val lastError: String?,
    val busy: Boolean
)
