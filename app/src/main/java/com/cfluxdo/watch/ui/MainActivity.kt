package com.cfluxdo.watch.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.cfluxdo.watch.R
import com.cfluxdo.watch.WatchApp
import com.cfluxdo.watch.model.CategoryItem
import com.cfluxdo.watch.model.TopicSummary
import com.cfluxdo.watch.net.AuthRequiredException
import com.cfluxdo.watch.net.CfChallengeException
import com.cfluxdo.watch.util.TimeFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = WatchApp.instance

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FeedAdapter

    private var currentFilter: String = "latest" // latest, hot, unread
    private var selectedCategory: CategoryItem? = null
    private var currentPage: Int = 0
    private var hasMore: Boolean = false
    private var isLoading: Boolean = false
    private var loadJob: Job? = null
    private var errorText: String? = null
    private var needsCfChallenge: Boolean = false

    private val topics = mutableListOf<TopicSummary>()
    private var categoryMap: Map<Int, CategoryItem> = emptyMap()

    private val categoryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            val id = res.data!!.getIntExtra("cat_id", -1)
            val name = res.data!!.getStringExtra("cat_name").orEmpty()
            val slug = res.data!!.getStringExtra("cat_slug").orEmpty()
            val color = res.data!!.getStringExtra("cat_color") ?: "E0A24D"
            if (id > 0) {
                selectedCategory = CategoryItem(id, name, slug, color)
                currentFilter = "latest"
                loadFirstPage()
            } else {
                selectedCategory = null
                loadFirstPage()
            }
        }
    }

    private val cfLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        needsCfChallenge = false
        loadFirstPage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swipeRefresh = findViewById(R.id.swipeRefresh)
        recyclerView = findViewById(R.id.recyclerView)

        swipeRefresh.isFocusable = false
        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.accent))
        swipeRefresh.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.surface_card))
        swipeRefresh.setOnRefreshListener { loadFirstPage() }

        categoryMap = app.discourse.getCachedCategoriesMap()
        adapter = FeedAdapter()

        val lm = LinearLayoutManager(this)
        recyclerView.layoutManager = lm
        recyclerView.adapter = adapter
        recyclerView.itemAnimator = null // Zero animation overhead on watch
        RotaryHelper.attachToRecyclerView(recyclerView)

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && hasMore && !isLoading) {
                    val lastVisible = lm.findLastVisibleItemPosition()
                    if (lastVisible >= topics.size - 3) {
                        loadNextPage()
                    }
                }
            }
        })

        // Wait briefly for Rust gateway bind on cold launch, then load feed
        uiScope.launch {
            var waited = 0
            while (!app.gateway.isRunning && waited < 1500) {
                delay(50)
                waited += 50
            }
            loadFirstPage()
            launch {
                try {
                    val cats = app.discourse.fetchCategories(forceRefresh = false)
                    categoryMap = cats.associateBy { it.id }
                    adapter.notifyDataSetChanged()
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyItemChanged(0)
        RotaryHelper.ensureFocus(recyclerView)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            RotaryHelper.ensureFocus(recyclerView)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (RotaryHelper.handleActivityGenericMotion(recyclerView, event)) {
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    private fun loadFirstPage() {
        loadJob?.cancel()
        currentPage = 0
        errorText = null
        needsCfChallenge = false
        isLoading = true
        adapter.notifyDataSetChanged()

        loadJob = uiScope.launch {
            try {
                val page = app.discourse.fetchTopics(
                    filter = currentFilter,
                    page = 0,
                    categoryId = selectedCategory?.id,
                    categorySlug = selectedCategory?.slug
                )
                topics.clear()
                topics.addAll(page.topics)
                hasMore = page.hasMore
                errorText = if (topics.isEmpty()) "暂无话题" else null
            } catch (e: CfChallengeException) {
                needsCfChallenge = true
                errorText = "触发 Cloudflare 验证，点击下方过盾"
            } catch (e: AuthRequiredException) {
                errorText = "此列表需要登录，请点顶部「扫码登录」"
            } catch (e: Exception) {
                errorText = e.message ?: "加载失败，请检查网络"
            } finally {
                isLoading = false
                swipeRefresh.isRefreshing = false
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun loadNextPage() {
        if (isLoading || !hasMore) return
        isLoading = true
        errorText = null
        adapter.notifyItemChanged(adapter.itemCount - 1)

        val nextPage = currentPage + 1
        loadJob = uiScope.launch {
            try {
                val page = app.discourse.fetchTopics(
                    filter = currentFilter,
                    page = nextPage,
                    categoryId = selectedCategory?.id,
                    categorySlug = selectedCategory?.slug
                )
                val existingIds = topics.mapTo(HashSet()) { it.id }
                val fresh = page.topics.filter { existingIds.add(it.id) }
                if (fresh.isNotEmpty()) {
                    topics.addAll(fresh)
                    currentPage = nextPage
                }
                hasMore = page.hasMore && fresh.isNotEmpty()
            } catch (e: CfChallengeException) {
                needsCfChallenge = true
                errorText = "触发 Cloudflare 验证"
            } catch (e: Exception) {
                errorText = e.message ?: "加载更多失败"
            } finally {
                isLoading = false
                adapter.notifyDataSetChanged()
            }
        }
    }

    private inner class FeedAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val TYPE_HEADER = 0
        private val TYPE_TOPIC = 1
        private val TYPE_FOOTER = 2

        override fun getItemCount(): Int = topics.size + 2

        override fun getItemViewType(position: Int): Int = when (position) {
            0 -> TYPE_HEADER
            itemCount - 1 -> TYPE_FOOTER
            else -> TYPE_TOPIC
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> HeaderVH(inf.inflate(R.layout.item_feed_header, parent, false))
                TYPE_FOOTER -> FooterVH(inf.inflate(R.layout.item_footer, parent, false))
                else -> TopicVH(inf.inflate(R.layout.item_topic, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is HeaderVH -> holder.bind()
                is TopicVH -> holder.bind(topics[position - 1])
                is FooterVH -> holder.bind()
            }
        }
    }

    private inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val netDot: View = view.findViewById(R.id.netStatusDot)
        private val btnAccount: TextView = view.findViewById(R.id.btnAccount)
        private val btnSettings: TextView = view.findViewById(R.id.btnSettings)
        private val tabLatest: TextView = view.findViewById(R.id.tabLatest)
        private val tabHot: TextView = view.findViewById(R.id.tabHot)
        private val tabUnread: TextView = view.findViewById(R.id.tabUnread)
        private val tabCategories: TextView = view.findViewById(R.id.tabCategories)
        private val tvActiveCategory: TextView = view.findViewById(R.id.tvActiveCategory)

        fun bind() {
            val status = app.gateway.getSelectStatus()
            val dotColor = when {
                status.running && status.winnerIp != null -> ContextCompat.getColor(this@MainActivity, R.color.status_ok)
                status.running -> ContextCompat.getColor(this@MainActivity, R.color.status_warn)
                else -> ContextCompat.getColor(this@MainActivity, R.color.status_err)
            }
            (netDot.background.mutate() as? GradientDrawable)?.setColor(dotColor)

            val loggedIn = app.cookieStore.hasLoginSession()
            val uname = app.prefs.cachedUsername
            btnAccount.text = if (loggedIn && !uname.isNullOrBlank()) "@$uname" else getString(R.string.qr_login)
            btnAccount.setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (loggedIn) R.color.status_ok else R.color.accent
                )
            )
            btnAccount.setOnClickListener {
                startActivity(Intent(this@MainActivity, QrLoginActivity::class.java))
            }
            btnSettings.setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }

            styleTab(tabLatest, selectedCategory == null && currentFilter == "latest")
            styleTab(tabHot, selectedCategory == null && currentFilter == "hot")
            styleTab(tabUnread, selectedCategory == null && currentFilter == "unread")
            styleTab(tabCategories, selectedCategory != null)

            tabLatest.setOnClickListener {
                selectedCategory = null
                currentFilter = "latest"
                loadFirstPage()
            }
            tabHot.setOnClickListener {
                selectedCategory = null
                currentFilter = "hot"
                loadFirstPage()
            }
            tabUnread.setOnClickListener {
                selectedCategory = null
                currentFilter = "unread"
                loadFirstPage()
            }
            tabCategories.setOnClickListener {
                categoryLauncher.launch(Intent(this@MainActivity, CategoriesActivity::class.java))
            }

            val cat = selectedCategory
            if (cat != null) {
                tvActiveCategory.visibility = View.VISIBLE
                tvActiveCategory.text = "${cat.name} ×"
                tvActiveCategory.setOnClickListener {
                    selectedCategory = null
                    loadFirstPage()
                }
            } else {
                tvActiveCategory.visibility = View.GONE
            }
        }

        private fun styleTab(tv: TextView, active: Boolean) {
            tv.setBackgroundResource(if (active) R.drawable.bg_pill_accent else R.drawable.bg_pill)
            tv.setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (active) R.color.accent else R.color.text_secondary
                )
            )
        }
    }

    private inner class TopicVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        private val viewDot: View = view.findViewById(R.id.viewCategoryDot)
        private val tvCategory: TextView = view.findViewById(R.id.tvCategory)
        private val tvMeta: TextView = view.findViewById(R.id.tvMeta)

        fun bind(topic: TopicSummary) {
            tvTitle.text = topic.title
            tvTitle.textSize = (app.prefs.fontSizeSp - 1).coerceIn(12, 17).toFloat()

            val cat = categoryMap[topic.categoryId]
            tvCategory.text = cat?.name ?: "综合"
            val parsedColor = try {
                Color.parseColor("#${cat?.colorHex ?: "E0A24D"}")
            } catch (_: Exception) {
                ContextCompat.getColor(this@MainActivity, R.color.accent)
            }
            (viewDot.background.mutate() as? GradientDrawable)?.setColor(parsedColor)

            val rel = TimeFormat.relative(topic.lastPostedAt ?: topic.createdAt)
            val unreadTag = if (topic.unreadPosts > 0) " +${topic.unreadPosts}" else ""
            tvMeta.text = "${topic.replyCount}评$unreadTag · $rel"

            itemView.setOnClickListener {
                val intent = Intent(this@MainActivity, TopicDetailActivity::class.java).apply {
                    putExtra("topic_id", topic.id)
                    putExtra("topic_title", topic.title)
                }
                startActivity(intent)
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
                cfLauncher.launch(Intent(this@MainActivity, CfChallengeActivity::class.java))
            }

            if (errorText != null) {
                tvMsg.visibility = View.VISIBLE
                tvMsg.text = errorText
            } else {
                tvMsg.visibility = View.GONE
            }

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
                        if (topics.isEmpty()) loadFirstPage() else loadNextPage()
                    }
                }
                hasMore -> {
                    btnAction.visibility = View.VISIBLE
                    btnAction.text = getString(R.string.load_more)
                    btnAction.setOnClickListener { loadNextPage() }
                }
                topics.isNotEmpty() -> {
                    btnAction.visibility = View.VISIBLE
                    btnAction.text = getString(R.string.no_more)
                    btnAction.setOnClickListener(null)
                }
                else -> {
                    btnAction.visibility = View.GONE
                }
            }
        }
    }
}
