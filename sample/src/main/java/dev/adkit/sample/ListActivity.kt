package dev.adkit.sample

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.adkit.AdKit
import dev.adkit.AdNativeView

/** Natives inside a RecyclerView every 6 rows; `retainKey` keeps each ad while scrolling. */
class ListActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val list = RecyclerView(this)
        list.fitsSystemWindows = true
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = Adapter()
        setContentView(list)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AdKit.showInterstitial(this@ListActivity, "back_press") { finish() }
            }
        })
    }

    private class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 60
        override fun getItemViewType(position: Int) = if (position % 6 == 3) AD else ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val layout = if (viewType == AD) R.layout.item_ad else android.R.layout.simple_list_item_1
            return object : RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v: View = holder.itemView
            if (v is AdNativeView) v.retainKey = "row$position"
            else (v as TextView).text = "Item $position"
        }

        companion object {
            const val AD = 1
            const val ROW = 0
        }
    }
}
