package com.cfluxdo.watch.ui

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cfluxdo.watch.R
import com.cfluxdo.watch.WatchApp
import com.cfluxdo.watch.model.PostItem
import com.cfluxdo.watch.model.TopicDetailData
import com.cfluxdo.watch.net.CfChallengeException
import com.cfluxdo.watch.render.DiscourseHtmlRenderer
import com.cfluxdo.watch.render.PostBlock
import com.cfluxdo.watch.util.TimeFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watch-optimized topic reader for OPPO Watch X2 (466x466 round OLED).
 * Uses 100% native Views + Spanned (NO WebView) for instant rendering, low RAM, and zero heat.
 */
class TopicDetailActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = WatchApp.instance

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PostAdapter

    private var topicId: Int = 0
    private var topicTitle: String = ""
    private var detail: TopicDetailData? = null
    private var onlyOp: Boolean = false

    private val posts = mutableListOf<PostItem>()
    private val parsedBlocks = HashMap<Int, List<PostBlock>>()
    private val loadedPostIds = HashSet<Int>()

    private var isLoading: Boolean = false
    private var errorText: String? = null
    private var needsCfChallenge: Boolean = false

    private val cfLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        needsCfChallenge = false
        loadTopic()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_topic_detail)

        topicId = intent.getIntExtra("topic_id", 0)
        topicTitle = intent.getStringExtra("topic_title").orEmpty()
        if (topicId <= 0) {
            finish()
            return
        }

        recyclerView = findViewById(R.id.recyclerView)
        adapter = PostAdapter()
        val lm = LinearLayoutManager(this)
        recyclerView.layoutManager = lm
        recyclerView.adapter = adapter
        recyclerView.itemAnimator = null
        RotaryHelper.attachToRecyclerView(recyclerView)

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !isLoading && hasMorePosts()) {
                    val last = lm.findLastVisibleItemPosition()
                    if (last >= posts.size - 2) {
                        loadMorePosts()
                    }
                }
            }
        })

        loadTopic()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    private fun hasMorePosts(): Boolean {
        val stream = detail?.streamPostIds ?: return false
        return stream.any { !loadedPostIds.contains(it) }
    }

    private fun loadTopic() {
        isLoading = true
        errorText = null
        needsCfChallenge = false
        adapter.notifyDataSetChanged()

        uiScope.launch {
            try {
                val filterUser = if (onlyOp) detail?.opUsername?.takeIf { it.isNotBlank() } else null
                val data = app.discourse.fetchTopicDetail(topicId, filterUser)
                val parsed = withContext(Dispatchers.Default) {
                    data.posts.associate { it.id to DiscourseHtmlRenderer.parse(it.cookedHtml) }
                }
                detail = data
                if (data.title.isNotBlank()) topicTitle = data.title
                posts.clear()
                loadedPostIds.clear()
                parsedBlocks.clear()
                posts.addAll(data.posts)
                data.posts.forEach { loadedPostIds.add(it.id) }
                parsedBlocks.putAll(parsed)
            } catch (e: CfChallengeException) {
                needsCfChallenge = true
                errorText = "需要完成 Cloudflare 验证"
            } catch (e: Exception) {
                errorText = e.message ?: "加载失败"
            } finally {
                isLoading = false
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun loadMorePosts() {
        val stream = detail?.streamPostIds ?: return
        val nextIds = stream.filter { !loadedPostIds.contains(it) }.take(15)
        if (nextIds.isEmpty()) return

        isLoading = true
        errorText = null
        adapter.notifyItemChanged(adapter.itemCount - 1)

        uiScope.launch {
            try {
                val more = app.discourse.fetchPostsByIds(topicId, nextIds)
                val parsed = withContext(Dispatchers.Default) {
                    more.associate { it.id to DiscourseHtmlRenderer.parse(it.cookedHtml) }
                }
                for (p in more) {
                    if (loadedPostIds.add(p.id)) {
                        posts.add(p)
                    }
                }
                parsedBlocks.putAll(parsed)
            } catch (e: CfChallengeException) {
                needsCfChallenge = true
                errorText = "需要完成 Cloudflare 验证"
            } catch (e: Exception) {
                errorText = e.message ?: "加载更多楼层失败"
            } finally {
                isLoading = false
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun showImageViewer(url: String) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_image_viewer)
        val iv = dialog.findViewById<ImageView>(R.id.ivFullImage)
        val hint = dialog.findViewById<TextView>(R.id.tvImgHint)
        hint.text = "图片加载中…"
        iv.setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.tvImgHint).setOnClickListener { dialog.dismiss() }
        dialog.show()

        uiScope.launch {
            val bmp = app.imageLoader.load(url, maxWidthPx = 460)
            if (!dialog.isShowing) return@launch
            if (bmp != null) {
                iv.setImageBitmap(bmp)
                hint.text = "点击关闭"
            } else {
                hint.text = "图片加载失败，点击关闭"
            }
        }
    }

    private inner class PostAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val TYPE_HEADER = 0
        private val TYPE_POST = 1
        private val TYPE_FOOTER = 2

        override fun getItemCount(): Int = posts.size + 2

        override fun getItemViewType(position: Int): Int = when (position) {
            0 -> TYPE_HEADER
            itemCount - 1 -> TYPE_FOOTER
            else -> TYPE_POST
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> TopicHeaderVH(inf.inflate(R.layout.item_topic_header, parent, false))
                TYPE_FOOTER -> FooterVH(inf.inflate(R.layout.item_footer, parent, false))
                else -> PostVH(inf.inflate(R.layout.item_post, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is TopicHeaderVH -> holder.bind()
                is PostVH -> holder.bind(posts[position - 1], position - 1)
                is FooterVH -> holder.bind()
            }
        }
    }

    private inner class TopicHeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tvTopicTitle)
        private val tvMeta: TextView = view.findViewById(R.id.tvTopicMeta)
        private val btnFontDec: TextView = view.findViewById(R.id.btnFontDec)
        private val btnFontInc: TextView = view.findViewById(R.id.btnFontInc)
        private val btnOnlyOp: TextView = view.findViewById(R.id.btnOnlyOp)

        fun bind() {
            tvTitle.text = topicTitle
            val d = detail
            tvMeta.text = if (d != null) {
                "楼主 @${d.opUsername} · ${posts.size}/${d.postsCount}楼"
            } else {
                getString(R.string.loading)
            }

            btnFontDec.setOnClickListener {
                app.prefs.fontSizeSp = (app.prefs.fontSizeSp - 1).coerceAtLeast(12)
                adapter.notifyDataSetChanged()
            }
            btnFontInc.setOnClickListener {
                app.prefs.fontSizeSp = (app.prefs.fontSizeSp + 1).coerceAtMost(21)
                adapter.notifyDataSetChanged()
            }

            btnOnlyOp.text = if (onlyOp) getString(R.string.all_posts) else getString(R.string.only_op)
            btnOnlyOp.setBackgroundResource(if (onlyOp) R.drawable.bg_pill_accent else R.drawable.bg_pill)
            btnOnlyOp.setTextColor(
                ContextCompat.getColor(
                    this@TopicDetailActivity,
                    if (onlyOp) R.color.accent else R.color.text_primary
                )
            )
            btnOnlyOp.setOnClickListener {
                if (detail == null) return@setOnClickListener
                onlyOp = !onlyOp
                loadTopic()
            }
        }
    }

    private inner class PostVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvAuthor: TextView = view.findViewById(R.id.tvAuthor)
        private val tvFloorTime: TextView = view.findViewById(R.id.tvFloorTime)
        private val contentContainer: LinearLayout = view.findViewById(R.id.contentContainer)
        private val btnLike: TextView = view.findViewById(R.id.btnLike)

        fun bind(post: PostItem, postIndex: Int) {
            val isOp = post.username == detail?.opUsername
            val opTag = if (isOp) " [楼主]" else ""
            val replyTag = if (post.replyToPostNumber != null) " ↩#${post.replyToPostNumber}" else ""
            tvAuthor.text = "${post.displayName}$opTag$replyTag"
            tvFloorTime.text = "#${post.postNumber} · ${TimeFormat.relative(post.createdAt)}"

            val fontSize = app.prefs.fontSizeSp.toFloat()
            val autoImg = app.prefs.autoLoadImages
            val blocks = parsedBlocks[post.id] ?: emptyList()

            contentContainer.removeAllViews()
            val dp6 = RotaryHelper.dp(contentContainer, 6)
            val dp8 = RotaryHelper.dp(contentContainer, 8)

            for (block in blocks) {
                when (block) {
                    is PostBlock.RichText -> {
                        val tv = TextView(this@TopicDetailActivity).apply {
                            text = block.spanned
                            textSize = fontSize
                            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                            setLinkTextColor(ContextCompat.getColor(context, R.color.accent_blue))
                            setLineSpacing(0f, 1.26f)
                            movementMethod = LinkMovementMethod.getInstance()
                            setPadding(0, 0, 0, dp6)
                        }
                        contentContainer.addView(tv)
                    }
                    is PostBlock.Quote -> {
                        val box = TextView(this@TopicDetailActivity).apply {
                            val header = if (block.author.isNotBlank()) "${block.author}\n" else ""
                            text = "$header${block.text}"
                            textSize = (fontSize - 2f).coerceAtLeast(11f)
                            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                            setBackgroundResource(R.drawable.bg_quote)
                            setPadding(dp8, dp6, dp8, dp6)
                            setLineSpacing(0f, 1.18f)
                        }
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp6 }
                        contentContainer.addView(box, lp)
                    }
                    is PostBlock.Code -> {
                        val hsv = HorizontalScrollView(this@TopicDetailActivity).apply {
                            setBackgroundResource(R.drawable.bg_code)
                            isHorizontalScrollBarEnabled = false
                        }
                        val codeTv = TextView(this@TopicDetailActivity).apply {
                            text = block.text
                            textSize = (fontSize - 3f).coerceAtLeast(10f)
                            typeface = Typeface.MONOSPACE
                            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                            setPadding(dp8, dp6, dp8, dp6)
                        }
                        hsv.addView(codeTv)
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp6 }
                        contentContainer.addView(hsv, lp)
                    }
                    is PostBlock.Spoiler -> {
                        var expanded = false
                        val tv = TextView(this@TopicDetailActivity).apply {
                            text = "▸ ${block.summary}"
                            textSize = (fontSize - 1f).coerceAtLeast(11f)
                            setTextColor(ContextCompat.getColor(context, R.color.accent))
                            setBackgroundResource(R.drawable.bg_quote)
                            setPadding(dp8, dp6, dp8, dp6)
                            setOnClickListener {
                                expanded = !expanded
                                text = if (expanded) {
                                    "▾ ${block.summary}\n${block.body}"
                                } else {
                                    "▸ ${block.summary}"
                                }
                                setTextColor(
                                    ContextCompat.getColor(
                                        context,
                                        if (expanded) R.color.text_primary else R.color.accent
                                    )
                                )
                            }
                        }
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp6 }
                        contentContainer.addView(tv, lp)
                    }
                    is PostBlock.Image -> {
                        if (autoImg) {
                            val iv = ImageView(this@TopicDetailActivity).apply {
                                adjustViewBounds = true
                                maxHeight = RotaryHelper.dp(this, 160)
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                setOnClickListener { showImageViewer(block.url) }
                            }
                            val lp = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = dp6 }
                            contentContainer.addView(iv, lp)
                            uiScope.launch {
                                val bmp: Bitmap? = app.imageLoader.load(block.url, maxWidthPx = 320)
                                if (bmp != null) {
                                    iv.setImageBitmap(bmp)
                                }
                            }
                        } else {
                            val label = if (block.alt.isNotBlank()) {
                                "🖼 ${block.alt.take(18)}"
                            } else {
                                "🖼 ${getString(R.string.tap_to_load_image)}"
                            }
                            val pill = TextView(this@TopicDetailActivity).apply {
                                text = label
                                textSize = 12f
                                setTextColor(ContextCompat.getColor(context, R.color.accent_blue))
                                setBackgroundResource(R.drawable.bg_pill)
                                setPadding(dp8, dp6, dp8, dp6)
                                setOnClickListener { showImageViewer(block.url) }
                            }
                            val lp = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = dp6 }
                            contentContainer.addView(pill, lp)
                        }
                    }
                }
            }

            // Like button
            val heart = if (post.liked) "♥" else "♡"
            btnLike.text = if (post.likeCount > 0) "$heart ${post.likeCount}" else heart
            btnLike.setTextColor(
                ContextCompat.getColor(
                    this@TopicDetailActivity,
                    if (post.liked) R.color.status_err else R.color.text_muted
                )
            )
            btnLike.setOnClickListener {
                if (!app.cookieStore.hasLoginSession()) {
                    Toast.makeText(this@TopicDetailActivity, "请先扫码登录", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                uiScope.launch {
                    try {
                        app.discourse.toggleLike(post.id, post.liked)
                        val updated = post.copy(
                            liked = !post.liked,
                            likeCount = (post.likeCount + if (post.liked) -1 else 1).coerceAtLeast(0)
                        )
                        if (postIndex in posts.indices) {
                            posts[postIndex] = updated
                            adapter.notifyItemChanged(postIndex + 1)
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@TopicDetailActivity, e.message ?: "操作失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private inner class FooterVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvMsg: TextView = view.findViewById(R.id.tvFooterMsg)
        private val btnAction: TextView = view.findViewById(R.id.btnFooterAction)
        private val btnCf: TextView = view.findViewById(R.id.btnFooterCf)

        fun bind() {
            btnCf.visibility = if (needsCfChallenge) View.VISIBLE else View.GONE
            btnCf.setOnClickListener {
                cfLauncher.launch(Intent(this@TopicDetailActivity, CfChallengeActivity::class.java))
            }

            if (errorText != null) {
                tvMsg.visibility = View.VISIBLE
                tvMsg.text = errorText
            } else {
                tvMsg.visibility = View.GONE
            }

            val remaining = (detail?.streamPostIds?.count { !loadedPostIds.contains(it) }) ?: 0
            when {
                isLoading -> {
                    btnAction.visibility = View.VISIBLE
                    btnAction.text = getString(R.string.loading)
                    btnAction.setOnClickListener(null)
                }
                errorText != null -> {
                    btnAction.visibility = View.VISIBLE
                    btnAction.text = getString(R.string.retry)
                    btnAction.setOnClickListener {
                        if (posts.isEmpty()) loadTopic() else loadMorePosts()
                    }
                }
                remaining > 0 -> {
                    btnAction.visibility = View.VISIBLE
                    btnAction.text = "加载更多（剩 $remaining 楼）"
                    btnAction.setOnClickListener { loadMorePosts() }
                }
                posts.isNotEmpty() -> {
                    btnAction.visibility = View.VISIBLE
                    btnAction.text = "已读完全部 ${posts.size} 楼"
                    btnAction.setOnClickListener(null)
                }
                else -> btnAction.visibility = View.GONE
            }
        }
    }
}
