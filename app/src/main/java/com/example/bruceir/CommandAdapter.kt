package com.example.bruceir

import android.graphics.Color
import android.hardware.ConsumerIrManager
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.animation.ValueAnimator
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import android.widget.TextView
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.util.concurrent.Executors

class CommandAdapter(
    private var items: MutableList<Any>,
    private val irManager: ConsumerIrManager?,
    private val onAction: (ActionType, Any, Int) -> Unit,
    private val onCommandSent: (Command) -> Unit
) : RecyclerView.Adapter<CommandAdapter.VH>() {

    fun updateList(newList: MutableList<Any>) {
        this.items = newList
        notifyDataSetChanged()
    }

    enum class ActionType { DELETE, EDIT, OPEN, MOVE, ADD_TO_MACRO }
    private val executor = Executors.newSingleThreadExecutor()
    private var isEditMode = false
    private var isListView = false
    private val toneGen = try { ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (e: Exception) { null }

    fun setEditMode(enabled: Boolean) {
        this.isEditMode = enabled
        notifyDataSetChanged()
    }

    fun setListView(enabled: Boolean) {
        this.isListView = enabled
        notifyDataSetChanged()
    }

    override fun getItemViewType(pos: Int): Int {
        val baseType = if (items[pos] is IrFolder) 0 else 1
        return if (isListView) baseType + 10 else baseType
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val useList = viewType >= 10
        val layout = if (useList) android.R.layout.simple_list_item_2 else R.layout.item_command
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val isList = getItemViewType(position) >= 10
        
        if (isList) {
            val tv1 = holder.itemView.findViewById<TextView>(android.R.id.text1)
            val tv2 = holder.itemView.findViewById<TextView>(android.R.id.text2)
            tv1?.text = if (item is IrFolder) item.name else (item as Command).name
            tv2?.text = if (item is IrFolder) "[FOLDER]" else "[COMMAND]"
            holder.itemView.setOnClickListener {
                handleItemClick(item, position, holder.itemView)
            }
            holder.itemView.setOnLongClickListener {
                onAction(ActionType.ADD_TO_MACRO, item, position)
                true
            }
            return
        }

        if (item is IrFolder) {
            holder.tvName?.text = item.name
            
            when (item.name) {
                "RECENTLY USED" -> {
                    holder.ivIcon?.setImageResource(android.R.drawable.ic_menu_recent_history)
                    holder.card?.setCardBackgroundColor(Color.parseColor(if (isEditMode) "#880E4F" else "#455A64"))
                }
                "DOWNLOADED" -> {
                    holder.ivIcon?.setImageResource(android.R.drawable.stat_sys_download_done)
                    holder.card?.setCardBackgroundColor(Color.parseColor(if (isEditMode) "#00695C" else "#00796B"))
                }
                else -> {
                    holder.ivIcon?.setImageResource(android.R.drawable.ic_menu_gallery)
                    holder.card?.setCardBackgroundColor(Color.parseColor(if (isEditMode) "#C2185B" else "#37474F"))
                }
            }
            
            holder.itemView.setOnClickListener {
                handleItemClick(item, position, holder.itemView)
            }
            holder.itemView.setOnLongClickListener {
                if (!isEditMode) onAction(ActionType.EDIT, item, position)
                true
            }
        } else if (item is Command) {
            holder.tvName?.text = item.name
            
            val customIcon = getCustomIcon(item.iconName)
            if (customIcon != 0) holder.ivIcon?.setImageResource(customIcon)
            else holder.ivIcon?.setImageResource(getIconForName(item.name))
            
            // Kolorowanie specjalne dla POWER lub koloru użytkownika
            val color = if (isEditMode) {
                "#F4511E"
            } else {
                item.colorHex ?: if (item.name.uppercase().contains("POWER")) "#E53935" else "#1E88E5"
            }
            holder.card?.setCardBackgroundColor(Color.parseColor(color))
            
            holder.itemView.setOnClickListener {
                handleItemClick(item, position, holder.itemView)
            }
            holder.itemView.setOnLongClickListener {
                if (!isEditMode) {
                    onAction(ActionType.ADD_TO_MACRO, item, position)
                } else {
                    onAction(ActionType.EDIT, item, position)
                }
                true
            }
        }
    }

    private fun handleItemClick(item: Any, position: Int, view: View) {
        if (isEditMode) {
            // W trybie edycji (otwarta kłódka) zwykły klik otwiera edycję
            onAction(ActionType.EDIT, item, position)
            return
        }

        if (item is Command) {
            // Animacja kliknięcia (Skala + Alpha) - błyskawiczna reakcja wizualna
            view.animate().scaleX(0.95f).scaleY(0.95f).alpha(0.6f).setDuration(70).withEndAction {
                view.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(70).start()
            }.start()

            // Dźwięk i transmisja w osobnym wątku, aby nie blokować UI
            executor.execute {
                try {
                    // Krótki beep
                    toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 100)
                    
                    // Właściwa transmisja IR
                    irManager?.transmit(item.frequency, item.pattern)
                } catch (e: Exception) {
                    android.util.Log.e("BruceIR", "Error during transmit/beep: ${e.message}")
                }
            }
            
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onCommandSent(item)
        } else if (item is IrFolder) {
            onAction(ActionType.OPEN, item, position)
        }
    }

    private fun getCustomIcon(name: String?): Int {
        return when(name) {
            "POWER" -> android.R.drawable.ic_lock_power_off
            "VOLUME" -> android.R.drawable.ic_lock_silent_mode_off
            "MUTE" -> android.R.drawable.ic_lock_silent_mode
            "PLAY" -> android.R.drawable.ic_media_play
            "PAUSE" -> android.R.drawable.ic_media_pause
            "STOP" -> android.R.drawable.ic_delete
            "UP" -> R.drawable.ic_menu_upload
            "DOWN" -> R.drawable.ic_menu_download
            "LEFT" -> R.drawable.ic_menu_left
            "RIGHT" -> R.drawable.ic_menu_right
            "STAR" -> android.R.drawable.btn_star_big_on
            "HEART" -> android.R.drawable.ic_menu_myplaces
            "BRIGHTNESS" -> android.R.drawable.ic_menu_day
            "CONTRAST" -> android.R.drawable.ic_menu_manage
            "HOME" -> android.R.drawable.ic_menu_directions
            "SETTINGS" -> android.R.drawable.ic_menu_preferences
            "INFO" -> android.R.drawable.ic_dialog_info
            "CAMERA" -> android.R.drawable.ic_menu_camera
            "MIC" -> android.R.drawable.ic_btn_speak_now
            "SEARCH" -> android.R.drawable.ic_menu_search
            "SEND" -> android.R.drawable.ic_menu_send
            "LOCK" -> android.R.drawable.ic_lock_lock
            "UNLOCK" -> android.R.drawable.ic_partial_secure
            "LIGHT" -> android.R.drawable.button_onoff_indicator_on
            "WIFI" -> android.R.drawable.stat_sys_phone_call
            "BATTERY" -> android.R.drawable.ic_lock_idle_low_battery
            "AC" -> android.R.drawable.ic_menu_compass
            "FAN" -> android.R.drawable.ic_menu_rotate
            "TV" -> android.R.drawable.ic_menu_slideshow
            "OK" -> android.R.drawable.checkbox_on_background
            "CANCEL" -> android.R.drawable.ic_menu_close_clear_cancel
            "PLUS" -> android.R.drawable.ic_menu_add
            "MINUS" -> android.R.drawable.ic_delete
            else -> 0
        }
    }

    private fun getIconForName(name: String): Int {
        val n = name.uppercase()
        return when {
            n.contains("POWER") || n.contains("ON") || n.contains("OFF") -> android.R.drawable.ic_lock_power_off
            n.contains("VOL") || n.contains("VOLUME") || n.contains("GŁOŚ") -> android.R.drawable.ic_lock_silent_mode_off
            n.contains("MUTE") || n.contains("WYCISZ") -> android.R.drawable.ic_lock_silent_mode
            n.contains("CH") || n.contains("CHANNEL") || n.contains("KANAŁ") -> android.R.drawable.ic_menu_sort_by_size
            n.contains("PLAY") -> android.R.drawable.ic_media_play
            n.contains("PAUSE") -> android.R.drawable.ic_media_pause
            n.contains("STOP") -> android.R.drawable.ic_delete
            n.contains("BACK") || n.contains("WRÓĆ") || n.contains("RETURN") -> android.R.drawable.ic_menu_revert
            n.contains("MENU") -> android.R.drawable.ic_menu_more
            n.contains("UP") || n.contains("GÓRA") -> R.drawable.ic_menu_upload
            n.contains("DOWN") || n.contains("DÓŁ") -> R.drawable.ic_menu_download
            n.contains("LEFT") || n.contains("LEWO") -> R.drawable.ic_menu_left
            n.contains("RIGHT") || n.contains("PRAWO") -> R.drawable.ic_menu_right
            else -> android.R.drawable.ic_media_play
        }
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView? = v.findViewById(R.id.tvCmdName)
        val ivIcon: ImageView? = v.findViewById(R.id.ivIcon)
        val card: MaterialCardView? = v.findViewById(R.id.cardRoot)
    }
}
