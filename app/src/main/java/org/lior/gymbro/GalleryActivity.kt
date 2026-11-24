package org.lior.gymbro

import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView

/** All the pictures taken of a station (opened by tapping its picture on the station page). */
class GalleryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        val s = intent.getStringExtra(EXTRA_ID)?.let { Repo.data.station(it) }
        if (s == null) { finish(); return }
        AppLog.i("gallery", "opened", "station" to s.name, "images" to s.images)
        findViewById<TextView>(R.id.gallery_title).text = s.name
        findViewById<ImageButton>(R.id.gallery_back).setOnClickListener { finish() }
        val count = findViewById<TextView>(R.id.gallery_count)
        val list = findViewById<RecyclerView>(R.id.gallery_list)
        val lm = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        list.layoutManager = lm
        PagerSnapHelper().attachToRecyclerView(list)
        val px = resources.displayMetrics.widthPixels
        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val iv = ImageView(parent.context)
                iv.layoutParams = RecyclerView.LayoutParams(parent.width.takeIf { it > 0 } ?: px, ViewGroup.LayoutParams.MATCH_PARENT)
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                return object : RecyclerView.ViewHolder(iv) {}
            }
            override fun getItemCount() = s.images.size
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                ImageLoader.into(holder.itemView as ImageView, Repo.imageFile(s.images[position]), px)
            }
        }
        fun showCount() {
            val i = lm.findFirstCompletelyVisibleItemPosition().coerceAtLeast(0)
            count.text = "${i + 1} / ${s.images.size}" + if (i == 0) "  (main picture)" else ""
        }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    showCount()
                    AppLog.i("gallery", "picture shown", "index" to lm.findFirstCompletelyVisibleItemPosition())
                }
            }
        })
        showCount()
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
