package com.example.bruceir

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter(
    private var items: List<BruceFile>,
    private val onClick: (BruceFile) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    data class BruceFile(
        val name: String,
        val isDir: Boolean,
        val fullPath: String
    )

    fun updateList(newList: List<BruceFile>) {
        items = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.text1.text = item.name
        
        val ext = item.name.substringAfterLast(".").lowercase()
        holder.text2.text = if (item.isDir) "[FOLDER]" else "[${ext.uppercase()}]"
        
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val text1: TextView = v.findViewById(android.R.id.text1)
        val text2: TextView = v.findViewById(android.R.id.text2)
    }
}
