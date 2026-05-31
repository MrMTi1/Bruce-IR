package com.example.bruceir

import android.graphics.Color
import android.hardware.ConsumerIrManager
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class CommandAdapter(
    private var items: MutableList<Any>,
    private val irManager: ConsumerIrManager?,
    private val onAction: (ActionType, Any, Int) -> Unit
) : RecyclerView.Adapter<CommandAdapter.VH>() {

    enum class ActionType { DELETE, EDIT, OPEN }
    private val executor = Executors.newSingleThreadExecutor()
    private var isEditMode = false

    fun setEditMode(enabled: Boolean) {
        this.isEditMode = enabled
        notifyDataSetChanged()
    }

    fun updateList(newList: MutableList<Any>) {
        this.items = newList
        notifyDataSetChanged()
    }

    override fun getItemViewType(pos: Int) = if (items[pos] is IrFolder) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_command, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        
        if (item is IrFolder) {
            holder.tvName.text = item.name
            holder.btnSend.text = "DIR"
            holder.btnSend.setBackgroundColor(if (isEditMode) Color.parseColor("#E91E63") else Color.parseColor("#546E7A"))
            holder.itemView.setOnClickListener {
                if (isEditMode) onAction(ActionType.EDIT, item, position)
                else onAction(ActionType.OPEN, item, position)
            }
        } else if (item is Command) {
            holder.tvName.text = item.name
            holder.btnSend.text = "IR"
            holder.btnSend.setBackgroundColor(if (isEditMode) Color.parseColor("#FF9800") else Color.parseColor("#2196F3"))
            
            holder.itemView.setOnClickListener {
                if (isEditMode) {
                    onAction(ActionType.EDIT, item, position)
                } else {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    executor.execute {
                        try { irManager?.transmit(item.frequency, item.pattern) } catch (e: Exception) {}
                    }
                }
            }
        }
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tvCmdName)
        val btnSend: Button = v.findViewById(R.id.btnSend)
        init {
            btnSend.isClickable = false
            btnSend.isFocusable = false
        }
    }
}
