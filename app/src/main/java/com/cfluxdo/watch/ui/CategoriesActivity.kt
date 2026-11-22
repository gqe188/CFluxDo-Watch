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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cfluxdo.watch.R
import com.cfluxdo.watch.WatchApp
import com.cfluxdo.watch.model.CategoryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CategoriesActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = WatchApp.instance

    private val categories = mutableListOf<CategoryItem>()
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: CatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_categories)

        recyclerView = findViewById(R.id.recyclerView)
        adapter = CatAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        recyclerView.itemAnimator = null
        RotaryHelper.attachToRecyclerView(recyclerView)

        uiScope.launch {
            try {
                val list = app.discourse.fetchCategories(forceRefresh = false)
                categories.clear()
                categories.add(CategoryItem(-1, "全部分类（清除筛选）", "", "E0A24D"))
                categories.addAll(list)
                adapter.notifyDataSetChanged()
            } catch (_: Exception) {
                categories.clear()
                categories.add(CategoryItem(-1, "分类加载失败，点击返回", "", "E06C75"))
                adapter.notifyDataSetChanged()
            }
        }
    }

    override fun onResume() {
        super.onResume()
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

    private inner class CatAdapter : RecyclerView.Adapter<CatVH>() {
        override fun getItemCount(): Int = categories.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CatVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
            return CatVH(v)
        }
        override fun onBindViewHolder(holder: CatVH, position: Int) = holder.bind(categories[position])
    }

    private inner class CatVH(view: View) : RecyclerView.ViewHolder(view) {
        private val dot: View = view.findViewById(R.id.viewCatDot)
        private val tvName: TextView = view.findViewById(R.id.tvCatName)

        fun bind(cat: CategoryItem) {
            tvName.text = cat.name
            val color = try {
                Color.parseColor("#${cat.colorHex}")
            } catch (_: Exception) {
                ContextCompat.getColor(this@CategoriesActivity, R.color.accent)
            }
            (dot.background.mutate() as? GradientDrawable)?.setColor(color)

            itemView.setOnClickListener {
                setResult(RESULT_OK, Intent().apply {
                    putExtra("cat_id", cat.id)
                    putExtra("cat_name", cat.name)
                    putExtra("cat_slug", cat.slug)
                    putExtra("cat_color", cat.colorHex)
                })
                finish()
            }
        }
    }
}
