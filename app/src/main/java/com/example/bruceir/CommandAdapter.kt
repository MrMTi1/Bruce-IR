package com.example.bruceir

import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.TypedValue
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.util.concurrent.Executors

class CommandAdapter(
    private val onAction: (ActionType, Any, Int) -> Unit,
    private val onCommandSent: (Command) -> Unit
) : ListAdapter<IrItem, CommandAdapter.VH>(ItemDiffCallback()) {

    interface ItemTouchHelperAdapter {
        fun onItemMove(fromPosition: Int, toPosition: Int): Boolean
        fun onItemDismiss(position: Int)
    }

    private val touchAdapter = object : ItemTouchHelperAdapter {
        override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
            val newList = currentList.toMutableList()
            java.util.Collections.swap(newList, fromPosition, toPosition)
            submitList(newList)
            return true
        }
        override fun onItemDismiss(position: Int) {}
    }

    fun getTouchAdapter() = touchAdapter

    fun updateList(newList: List<Any>) {
        submitList(newList.filterIsInstance<IrItem>().toList())
    }

    class ItemDiffCallback : DiffUtil.ItemCallback<IrItem>() {
        override fun areItemsTheSame(oldItem: IrItem, newItem: IrItem): Boolean {
            if (oldItem::class != newItem::class) return false
            return if (oldItem is IrFolder && newItem is IrFolder) {
                oldItem.name == newItem.name
            } else if (oldItem is Command && newItem is Command) {
                oldItem.name == newItem.name && oldItem.pattern.contentEquals(newItem.pattern)
            } else false
        }

        override fun areContentsTheSame(oldItem: IrItem, newItem: IrItem): Boolean {
            return if (oldItem is Command && newItem is Command) {
                oldItem.name == newItem.name && 
                oldItem.pattern.contentEquals(newItem.pattern) &&
                oldItem.iconName == newItem.iconName &&
                oldItem.colorHex == newItem.colorHex
            } else if (oldItem is IrFolder && newItem is IrFolder) {
                oldItem.name == newItem.name && oldItem.items.size == newItem.items.size
            } else {
                oldItem == newItem
            }
        }
    }

    enum class ActionType { DELETE, EDIT, OPEN, MOVE, ADD_TO_MACRO }
    private val executor = Executors.newSingleThreadExecutor()
    private var isEditMode = false
    private var isListView = false
    private var isCarouselMode = true
    private var isRootMenu = false
    private var isGroupedFolderMenu = false
    private var isSoundEnabled = true
    private var iconSizeDp = 40
    private val toneGen = try { ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (e: Exception) { null }

    fun setCarouselMode(enabled: Boolean) {
        this.isCarouselMode = enabled
        notifyDataSetChanged()
    }

    fun setRootMenu(enabled: Boolean) {
        isRootMenu = enabled
        notifyDataSetChanged()
    }

    fun setGroupedFolderMenu(enabled: Boolean) {
        isGroupedFolderMenu = enabled
        notifyDataSetChanged()
    }

    fun setEditMode(enabled: Boolean) {
        this.isEditMode = enabled
        notifyDataSetChanged()
    }

    fun setSoundEnabled(enabled: Boolean) {
        this.isSoundEnabled = enabled
    }

    fun setListView(enabled: Boolean) {
        this.isListView = enabled
        notifyDataSetChanged()
    }

    fun setIconSizeDp(sizeDp: Int) {
        iconSizeDp = sizeDp.coerceIn(24, 56)
        notifyDataSetChanged()
    }

    override fun getItemViewType(pos: Int): Int {
        if (isCarouselMode && getItem(pos) is IrFolder) return 20 // Large Carousel Card
        // Menu główne i listy folderów (np. „PILOTY") mają jednolity wygląd kart menu.
        if (isRootMenu || (isGroupedFolderMenu && getItem(pos) is IrFolder)) return 0
        
        val isFolder = getItem(pos) is IrFolder
        val baseType = if (isFolder) 0 else 1
        
        return if (isListView && !isCarouselMode) baseType + 10 else baseType
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = when (viewType) {
            20 -> R.layout.item_carousel
            0 -> R.layout.item_folder_grid // New grid layout for folders
            1 -> R.layout.item_command
            10, 11 -> R.layout.item_list
            else -> android.R.layout.simple_list_item_2
        }
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val type = getItemViewType(position)
        applyIconSize(holder, type)
        
        // COMMON BINDING FOR CAROUSEL & FOLDER GRID
        if (type == 0 && isRootMenu && item is Command) {
            holder.tvName?.text = item.name
            holder.tvEmoji?.apply {
                text = rootCommandIcon(context, item.name)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setTextColor(Color.parseColor("#62CDB1"))
            }
            holder.itemView.findViewById<TextView?>(R.id.tvFolderInfo)?.apply {
                text = rootCommandSubtitleRes(context, item.name)?.let(context::getString).orEmpty()
                visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            holder.itemView.findViewById<TextView?>(R.id.tvFolderArrow)?.visibility = View.VISIBLE
            holder.ivDelete?.visibility = View.GONE
            holder.card?.setCardBackgroundColor(Color.parseColor("#141B25"))
            holder.card?.strokeColor = Color.parseColor("#283545")
            holder.card?.strokeWidth =
                holder.itemView.resources.displayMetrics.density.toInt().coerceAtLeast(1)
            holder.card?.cardElevation = 0f
            holder.itemView.setOnClickListener { handleItemClick(item, position, holder.itemView) }
            holder.itemView.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onAction(ActionType.OPEN, item, position)
                true
            }
            return
        }

        if ((type == 20 || type == 0) && item is IrFolder) {
            val menuStyle = isRootMenu || isGroupedFolderMenu
            val rawTitle = if (isRootMenu) {
                val titleRes = rootCategoryTitleRes(item.name)
                if (titleRes == 0) item.name else holder.itemView.context.getString(titleRes)
            } else {
                item.name
            }
            // W kartach menu ikona jest osobną kolumną, więc nie duplikujemy emoji w tytule.
            holder.tvName?.text = if (menuStyle) stripLeadingEmoji(rawTitle) else rawTitle
            holder.tvEmoji?.apply {
                text = if (isRootMenu) {
                    when {
                        item.name.contains("USTAWIENIA PODCZERWIENI", ignoreCase = true) -> "⚙"
                        item.name.contains("PILOTY", ignoreCase = true) -> "▣"
                        else -> item.iconName ?: getEmojiForFolder(item.name)
                    }
                } else {
                    item.iconName ?: getEmojiForFolder(item.name)
                }
                if (menuStyle) {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                    setTextColor(Color.parseColor("#62CDB1"))
                }
            }
            holder.itemView.findViewById<TextView?>(R.id.tvFolderInfo)?.apply {
                if (isRootMenu) {
                    val subtitleRes = rootCategorySubtitleRes(item.name)
                    val subtitleResolved = if (subtitleRes == 0) "" else context.getString(subtitleRes)
                    text = subtitleResolved.ifEmpty {
                        // Fallback dla własnych folderów: liczba elementów zamiast pustego opisu.
                        val count = item.items.count { it is IrFolder }
                            .takeIf { it > 0 } ?: item.items.count { it is Command }
                        countLabel(context, count)
                    }
                } else if (isGroupedFolderMenu) {
                    val folderCount = item.items.count { it is IrFolder }
                    val itemCount = if (folderCount > 0) folderCount else item.items.count { it is Command }
                    text = countLabel(context, itemCount)
                } else {
                    text = null
                }
                visibility = if (menuStyle) View.VISIBLE else View.GONE
            }
            
            holder.ivDelete?.visibility =
                if (isEditMode) View.VISIBLE else View.GONE
            holder.ivDelete?.setOnClickListener { onAction(ActionType.DELETE, item, position) }
            holder.itemView.findViewById<TextView?>(R.id.tvFolderArrow)?.visibility =
                if (isEditMode) View.GONE else View.VISIBLE
            
            holder.card?.setCardBackgroundColor(
                when {
                    isEditMode -> Color.parseColor("#40212B")
                    else -> Color.parseColor("#141B25")
                }
            )
            holder.card?.strokeColor = Color.parseColor("#283545")
            holder.card?.strokeWidth =
                holder.itemView.resources.displayMetrics.density.toInt().coerceAtLeast(1)
            holder.card?.cardElevation = 0f
            if (isRootMenu || isGroupedFolderMenu) {
                holder.itemView.findViewById<TextView?>(R.id.tvFolderArrow)?.setTextColor(
                    Color.parseColor("#62CDB1")
                )
            }
            
            holder.itemView.setOnClickListener { handleItemClick(item, position, holder.itemView) }
            holder.itemView.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (isEditMode) {
                    onAction(ActionType.EDIT, item, position)
                }
                true
            }
            return
        }

        if (type >= 10) {
            val isFolder = item is IrFolder
            holder.tvName?.text = if (isFolder) item.name else (item as Command).name
            holder.itemView.findViewById<TextView>(R.id.tvItemKind)?.setText(
                if (isFolder) R.string.folder_item_kind else R.string.command_item_kind
            )
            holder.ivDelete?.visibility = if (isEditMode) View.VISIBLE else View.GONE
            holder.ivDelete?.setOnClickListener { onAction(ActionType.DELETE, item, position) }
            if (item is IrFolder) {
                holder.ivIcon?.visibility = View.GONE
                holder.tvEmoji?.visibility = View.VISIBLE
                holder.tvEmoji?.text = item.iconName ?: getEmojiForFolder(item.name)
                holder.card?.setCardBackgroundColor(
                    if (isEditMode) Color.parseColor("#880E4F") else folderColor(item)
                )
            } else if (item is Command) {
                val customResId = getCustomIcon(item.iconName)
                val techResId = getIconForName(item.name)
                val emoji = item.iconName?.takeIf { it.isNotEmpty() && customResId == 0 }
                    ?: getEmojiForName(item.name)
                if (emoji != null) {
                    holder.ivIcon?.visibility = View.GONE
                    holder.tvEmoji?.visibility = View.VISIBLE
                    holder.tvEmoji?.text = emoji
                } else {
                    holder.tvEmoji?.visibility = View.GONE
                    holder.ivIcon?.visibility = View.VISIBLE
                    holder.ivIcon?.setImageResource(
                        when {
                            customResId != 0 -> customResId
                            techResId != 0 -> techResId
                            else -> android.R.drawable.ic_media_play
                        }
                    )
                    holder.ivIcon?.setColorFilter(Color.WHITE)
                }
                holder.card?.setCardBackgroundColor(
                    if (isEditMode) Color.parseColor("#F4511E")
                    else Color.parseColor(item.colorHex ?: "#1E1E1E")
                )
            }
            holder.itemView.setOnClickListener { handleItemClick(item, position, holder.itemView) }
            holder.itemView.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (item is Command && item.frequency == 0) onAction(ActionType.OPEN, item, position)
                else if (isEditMode || isFolder) onAction(ActionType.EDIT, item, position)
                else onAction(ActionType.ADD_TO_MACRO, item, position)
                true
            }
            return
        }

        if (item is IrFolder) {
            holder.tvName?.text = item.name
            holder.ivDelete?.visibility = if (isEditMode) View.VISIBLE else View.GONE
            holder.ivDelete?.setOnClickListener { onAction(ActionType.DELETE, item, position) }

            val emoji = item.iconName ?: getEmojiForFolder(item.name)
            holder.ivIcon?.visibility = View.GONE
            holder.tvEmoji?.visibility = View.VISIBLE
            holder.tvEmoji?.text = emoji

            val folderColor = if (isRootMenu) {
                rootCategoryColor(item.name)
            } else {
                item.colorHex?.let(Color::parseColor) ?: when {
                    item.name.contains("SALON") || item.name.contains("LIVING") -> Color.parseColor("#1E88E5")
                    item.name.contains("SYPIALN") || item.name.contains("BEDROOM") -> Color.parseColor("#8E24AA")
                    item.name == "RECENTLY USED" -> Color.parseColor("#455A64")
                    item.name == "DOWNLOADED" -> Color.parseColor("#00796B")
                    else -> Color.parseColor("#37474F")
                }
            }
            
            holder.card?.setCardBackgroundColor(if (isEditMode) Color.parseColor("#880E4F") else folderColor)
            holder.card?.strokeColor = Color.parseColor("#33FFFFFF")
            holder.card?.strokeWidth = 2
            
            holder.itemView.setOnClickListener { handleItemClick(item, position, holder.itemView) }
            holder.itemView.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (!isEditMode) onAction(ActionType.EDIT, item, position)
                true
            }
            return
        }
else if (item is Command) {
            if (item.name.isBlank() || item.iconName == "spacer") {
                holder.card?.visibility = View.INVISIBLE
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
                return
            } else {
                holder.card?.visibility = View.VISIBLE
            }
            holder.tvName?.text = item.name
            holder.ivDelete?.visibility = if (isEditMode) View.VISIBLE else View.GONE
            holder.ivDelete?.setOnClickListener { onAction(ActionType.DELETE, item, position) }
            
            val iconString = item.iconName ?: ""
            val customResId = getCustomIcon(iconString)
            val techResId = getIconForName(item.name)
            val smartEmoji = getEmojiForName(item.name)
            
            if (iconString.isNotEmpty() && customResId == 0) {
                holder.ivIcon?.visibility = View.GONE
                holder.tvEmoji?.visibility = View.VISIBLE
                holder.tvEmoji?.text = iconString
            } else if (techResId != 0) {
                // Priority: Technical Android Icon (Better Industrial Look)
                holder.tvEmoji?.visibility = View.GONE
                holder.ivIcon?.visibility = View.VISIBLE
                holder.ivIcon?.setImageResource(techResId)
                
                // Tech Tinting
                val name = item.name.uppercase()
                when {
                    name.contains("POWER") || name.contains("OFF") || name.contains("RED") || name.contains("REC") -> 
                        holder.ivIcon?.setColorFilter(Color.parseColor("#E53935"))
                    name.contains("GREEN") || name.contains("ON") || name.contains("OK") -> 
                        holder.ivIcon?.setColorFilter(Color.parseColor("#43A047"))
                    else -> holder.ivIcon?.setColorFilter(Color.WHITE)
                }
            } else if (smartEmoji != null) {
                holder.ivIcon?.visibility = View.GONE
                holder.tvEmoji?.visibility = View.VISIBLE
                holder.tvEmoji?.text = smartEmoji
            } else {
                holder.tvEmoji?.visibility = View.GONE
                holder.ivIcon?.visibility = View.VISIBLE
                val resId = if (customResId != 0) customResId else android.R.drawable.ic_media_play
                holder.ivIcon?.setImageResource(resId)
                holder.ivIcon?.setColorFilter(Color.WHITE)
            }
            
            val colorString = if (isEditMode) "#F4511E" else (item.colorHex ?: "#1E1E1E")
            val baseColor = Color.parseColor(colorString)
            holder.card?.setCardBackgroundColor(baseColor)
            
            if (!isEditMode && item.colorHex != null) {
                holder.card?.strokeColor = baseColor
                holder.card?.strokeWidth = 4
                holder.card?.cardElevation = 4f
            } else {
                holder.card?.strokeColor = Color.parseColor("#1AFFFFFF")
                holder.card?.strokeWidth = 1
                holder.card?.cardElevation = 0f
            }
            
            holder.itemView.setOnClickListener { handleItemClick(item, position, holder.itemView) }
            holder.itemView.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (item.frequency == 0) {
                    onAction(ActionType.OPEN, item, position)
                } else if (!isEditMode) {
                    onAction(ActionType.ADD_TO_MACRO, item, position)
                } else {
                    onAction(ActionType.EDIT, item, position)
                }
                true
            }
        }
    }

    private fun applyIconSize(holder: VH, type: Int) {
        val density = holder.itemView.resources.displayMetrics.density
        val listIconSize = type >= 10
        val iconPx = ((iconSizeDp + if (listIconSize) 8 else 0) * density).toInt()
        val textScale = (iconSizeDp / 40f).coerceIn(0.75f, 1.25f)
        val emojiBaseSize = if (listIconSize) 32f else if (type == 20) 40f else 44f
        holder.tvEmoji?.setTextSize(TypedValue.COMPLEX_UNIT_SP, emojiBaseSize * textScale)
        holder.tvName?.let { name ->
            val baseSize = when {
                listIconSize -> 16f
                type == 20 || type == 0 -> 14f
                else -> 12f
            }
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSize * textScale)
        }
        holder.itemView.findViewById<TextView?>(R.id.tvItemKind)?.let { kind ->
            kind.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * textScale)
        }
        holder.itemView.findViewById<TextView?>(R.id.tvCarouselInfo)?.let { info ->
            info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * textScale)
        }
        holder.iconContainer?.layoutParams = holder.iconContainer?.layoutParams?.apply {
            width = iconPx
            height = iconPx
        }

        val card = holder.card ?: return
        if (type >= 10) {
            card.minimumHeight = maxOf((72 * density).toInt(), iconPx + (24 * density).toInt())
        } else {
            val cardParams = card.layoutParams ?: return
            cardParams.height = maxOf((88 * density).toInt(), ((iconSizeDp + 76) * density).toInt())
            card.layoutParams = cardParams
        }
    }

    /** Poprawna forma liczby elementów (1 item / N items). */
    private fun countLabel(context: android.content.Context, count: Int): String =
        if (count == 1) {
            context.getString(R.string.home_category_item_single)
        } else {
            context.getString(R.string.home_category_items, count)
        }

    /** Usuwa wiodące emoji/symbole z nazwy folderu (ikona jest rysowana osobno). */
    private fun stripLeadingEmoji(name: String): String {
        var index = 0
        while (index < name.length) {
            val ch = name[index]
            val isPlain = ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '+' || ch == '#'
            if (isPlain) break
            index++
        }
        val stripped = name.substring(index).trim()
        return stripped.ifBlank { name.trim() }
    }

    private fun rootCategoryColor(name: String): Int = when {        name.contains("PILOTY", ignoreCase = true) -> Color.parseColor("#4DA3FF")
        name.contains("LILYGO", ignoreCase = true) -> Color.parseColor("#43C59E")
        name.contains("WPA CRACKER", ignoreCase = true) -> Color.parseColor("#F2B84B")
        name.contains("WEB FILE HUB", ignoreCase = true) -> Color.parseColor("#A78BFA")
        name.contains("USTAWIENIA PODCZERWIENI", ignoreCase = true) -> Color.parseColor("#91A0B5")
        name.contains("USTAWIENIA", ignoreCase = true) -> Color.parseColor("#91A0B5")
        else -> Color.parseColor("#607D8B")
    }

    private fun rootCommandSubtitleRes(context: android.content.Context, name: String): Int? = when (name) {
        context.getString(R.string.ir_settings_add_remote) -> R.string.ir_settings_add_remote_desc
        context.getString(R.string.ir_settings_add_command) -> R.string.ir_settings_add_command_desc
        context.getString(R.string.ir_settings_import_file) -> R.string.ir_settings_import_file_desc
        context.getString(R.string.ir_settings_import_url) -> R.string.ir_settings_import_url_desc
        context.getString(R.string.ir_settings_export_backup) -> R.string.ir_settings_export_backup_desc
        context.getString(R.string.ir_settings_restore_backup) -> R.string.ir_settings_restore_backup_desc
        context.getString(R.string.ir_settings_options) -> R.string.ir_settings_options_desc
        context.getString(R.string.ir_settings_downloaded) -> R.string.ir_settings_downloaded_desc
        context.getString(R.string.ir_settings_tv_b_gone) -> R.string.ir_settings_tv_b_gone_desc
        else -> null
    }

    private fun rootCommandIcon(context: android.content.Context, name: String): String = when (name) {
        context.getString(R.string.ir_settings_add_remote) -> "▤"
        context.getString(R.string.ir_settings_add_command) -> "+"
        context.getString(R.string.ir_settings_import_file) -> "⇧"
        context.getString(R.string.ir_settings_import_url) -> "↗"
        context.getString(R.string.ir_settings_export_backup) -> "⇧"
        context.getString(R.string.ir_settings_restore_backup) -> "⇩"
        context.getString(R.string.ir_settings_options) -> "⚙"
        context.getString(R.string.ir_settings_downloaded) -> "⇄"
        context.getString(R.string.ir_settings_tv_b_gone) -> "⏻"
        else -> "›"
    }

    private fun rootCategoryTitleRes(name: String): Int = when {
        name.contains("USTAWIENIA PODCZERWIENI", ignoreCase = true) -> R.string.home_category_ir_settings_title
        name.contains("PILOTY", ignoreCase = true) -> R.string.home_category_ir_title
        name.contains("LILYGO", ignoreCase = true) -> R.string.home_category_bruce_title
        name.contains("WPA CRACKER", ignoreCase = true) -> R.string.home_category_wifi_title
        name.contains("WEB FILE HUB", ignoreCase = true) -> R.string.home_category_files_title
        name.contains("USTAWIENIA", ignoreCase = true) -> R.string.home_category_settings_title
        else -> 0
    }

    private fun rootCategorySubtitleRes(name: String): Int = when {
        name.contains("USTAWIENIA PODCZERWIENI", ignoreCase = true) -> R.string.home_category_ir_settings_subtitle
        name.contains("PILOTY", ignoreCase = true) -> R.string.home_category_ir_subtitle
        name.contains("LILYGO", ignoreCase = true) -> R.string.home_category_bruce_subtitle
        name.contains("WPA CRACKER", ignoreCase = true) -> R.string.home_category_wifi_subtitle
        name.contains("WEB FILE HUB", ignoreCase = true) -> R.string.home_category_files_subtitle
        name.contains("USTAWIENIA", ignoreCase = true) -> R.string.home_category_settings_subtitle
        else -> 0
    }

    private fun folderColor(item: IrFolder): Int {
        return item.colorHex?.let { Color.parseColor(it) } ?: when {
            item.name.contains("SALON") || item.name.contains("LIVING") -> Color.parseColor("#1E88E5")
            item.name.contains("SYPIALN") || item.name.contains("BEDROOM") -> Color.parseColor("#8E24AA")
            item.name == "RECENTLY USED" -> Color.parseColor("#455A64")
            item.name == "DOWNLOADED" -> Color.parseColor("#00796B")
            else -> Color.parseColor("#37474F")
        }
    }

    private fun handleItemClick(item: Any, position: Int, view: View) {
        if (isEditMode) {
            onAction(ActionType.EDIT, item, position)
            return
        }

        if (item is Command) {
            view.animate().scaleX(0.95f).scaleY(0.95f).alpha(0.6f).setDuration(70).withEndAction {
                view.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(70).start()
            }.start()

            val prefs = view.context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("haptic_enabled", true)) {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }

            if (item.pattern.contentEquals(intArrayOf(100, 100)) || item.frequency == 0) {
                onAction(ActionType.OPEN, item, position)
            } else {
                executor.execute {
                    if (isSoundEnabled) toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 100)
                }
                onCommandSent(item)
            }
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

    private fun getEmojiForName(name: String): String? {
        val n = name.uppercase()
        return when {
            n.contains("POWER") || n.contains("ON/OFF") || n.equals("ON") || n.equals("OFF") -> "🔴"
            n.contains("VOL+") || n.contains("GŁOŚ+") || n.contains("VOLUME UP") || n.contains("VOL UP") -> "🔊"
            n.contains("VOL-") || n.contains("CISZEJ") || n.contains("VOLUME DOWN") || n.contains("VOL DOWN") -> "🔉"
            n.contains("MUTE") || n.contains("WYCISZ") -> "🔇"
            n.contains("CH+") || n.contains("KANAŁ+") || n.contains("CHANNEL UP") || n.contains("CH UP") || n.contains("NEXT CHANNEL") -> "🔼"
            n.contains("CH-") || n.contains("KANAŁ-") || n.contains("CHANNEL DOWN") || n.contains("CH DOWN") || n.contains("PREV CHANNEL") -> "🔽"
            n.contains("PLAY") -> "▶️"
            n.contains("PAUSE") -> "⏸️"
            n.contains("STOP") -> "⏹️"
            n.contains("REC") || n.contains("NAGRYW") -> "⏺️"
            n.contains("BACK") || n.contains("WRÓĆ") || n.contains("RETURN") -> "🔙"
            n.contains("EXIT") || n.contains("WYJDŹ") -> "❌"
            n.contains("MENU") -> "📜"
            n.contains("SETTINGS") || n.contains("USTAWIENIA") || n.contains("SETUP") || n.contains("OPCJE") -> "⚙️"
            n.contains("UP") || n.contains("GÓRA") -> "🔼"
            n.contains("DOWN") || n.contains("DÓŁ") -> "🔽"
            n.contains("LEFT") || n.contains("LEWO") -> "◀️"
            n.contains("RIGHT") || n.contains("PRAWO") -> "▶️"
            n.contains("OK") || n.contains("ENTER") || n.contains("SELECT") || n.contains("WYBIERZ") -> "✅"
            n.contains("HOME") || n.contains("DOM") -> "🏠"
            n.contains("SOURCE") || n.contains("INPUT") || n.contains("WEJŚCIE") || n.contains("ŹRÓDŁO") -> "🔌"
            n.contains("INFO") -> "ℹ️"
            n.contains("LIGHT") || n.contains("ŚWIATŁO") -> "💡"
            n.contains("FAN") || n.contains("WENTYLATOR") -> "🌀"
            n.contains("AC") || n.contains("KLIMA") -> "❄️"
            n.contains("TEMP") -> "🌡️"
            n.contains("TV") || n.contains("TELEW") -> "📺"
            n.contains("SAT") -> "📡"
            n.contains("DVD") -> "📀"
            n.contains("AUDIO") || n.contains("AMP") -> "🔊"
            n.contains("SEARCH") || n.contains("SZUKAJ") -> "🔍"
            n.contains("LOCK") || n.contains("BLOKAD") -> "🔒"
            n.contains("RADIO") -> "📻"
            n.contains("MODE") -> "🔄"
            n.contains("NETFLIX") -> "🎥"
            n.contains("YOUTUBE") -> "📺"
            n.contains("GUIDE") -> "📖"
            n.contains("SLEEP") -> "🌙"
            n.contains("0") -> "0️⃣"
            n.contains("1") -> "1️⃣"
            n.contains("2") -> "2️⃣"
            n.contains("3") -> "3️⃣"
            n.contains("4") -> "4️⃣"
            n.contains("5") -> "5️⃣"
            n.contains("6") -> "6️⃣"
            n.contains("7") -> "7️⃣"
            n.contains("8") -> "8️⃣"
            n.contains("9") -> "9️⃣"
            n.contains("RED") || n.contains("CZERWO") -> "🔴"
            n.contains("GREEN") || n.contains("ZIELO") -> "🟢"
            n.contains("BLUE") || n.contains("NIEBIE") -> "🔵"
            n.contains("YELLOW") || n.contains("ŻÓŁT") -> "🟡"
            else -> null
        }
    }

    private fun getIconForName(name: String): Int {
        val n = name.uppercase()
        return when {
            n.contains("POWER") || n.contains("ON/OFF") || n.equals("ON") || n.equals("OFF") -> android.R.drawable.ic_lock_power_off
            n.contains("VOL+") || n.contains("GŁOŚ+") || n.contains("VOLUME UP") -> android.R.drawable.ic_input_add
            n.contains("VOL-") || n.contains("CISZEJ") || n.contains("VOLUME DOWN") -> android.R.drawable.ic_delete
            n.contains("MUTE") || n.contains("WYCISZ") -> android.R.drawable.ic_lock_silent_mode
            n.contains("CH+") || n.contains("KANAŁ+") || n.contains("CHANNEL UP") -> android.R.drawable.arrow_up_float
            n.contains("CH-") || n.contains("KANAŁ-") || n.contains("CHANNEL DOWN") -> android.R.drawable.arrow_down_float
            n.contains("PLAY") -> android.R.drawable.ic_media_play
            n.contains("PAUSE") -> android.R.drawable.ic_media_pause
            n.contains("STOP") -> android.R.drawable.ic_delete
            n.contains("REC") || n.contains("NAGRYW") -> android.R.drawable.presence_online
            n.contains("BACK") || n.contains("WRÓĆ") || n.contains("RETURN") || n.contains("EXIT") -> android.R.drawable.ic_menu_revert
            n.contains("MENU") -> android.R.drawable.ic_menu_more
            n.contains("UP") || n.contains("GÓRA") -> android.R.drawable.arrow_up_float
            n.contains("DOWN") || n.contains("DÓŁ") -> android.R.drawable.arrow_down_float
            n.contains("LEFT") || n.contains("LEWO") -> android.R.drawable.ic_media_previous
            n.contains("RIGHT") || n.contains("PRAWO") -> android.R.drawable.ic_media_next
            n.contains("OK") || n.contains("ENTER") || n.contains("SELECT") -> android.R.drawable.checkbox_on_background
            n.contains("LIGHT") || n.contains("ŚWIATŁO") -> android.R.drawable.button_onoff_indicator_on
            n.contains("WIFI") -> android.R.drawable.stat_sys_phone_call
            n.contains("BATERIA") || n.contains("BATTERY") -> android.R.drawable.ic_lock_idle_low_battery
            else -> 0 // Return 0 to indicate no technical icon found
        }
    }

    private fun getEmojiForFolder(name: String): String {
        val n = name.uppercase()
        return when {
            n.contains("SALON") || n.contains("LIVING") -> "📺"
            n.contains("SYPIALN") || n.contains("BEDROOM") -> "🛌"
            n.contains("KUCHNIA") || n.contains("KITCHEN") -> "☕"
            n.contains("RECENT") -> "🕒"
            n.contains("DOWNLOAD") -> "📥"
            n.contains("MACRO") -> "📝"
            n.contains("RF") || n.contains("SUB") -> "📶"
            n.contains("IR") || n.contains("INFRA") -> "📡"
            else -> "📁"
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView? = v.findViewById(R.id.tvCmdName) ?: v.findViewById(R.id.tvCarouselName)
        val ivIcon: ImageView? = v.findViewById(R.id.ivIcon)
        val tvEmoji: TextView? = v.findViewById(R.id.tvEmoji) ?: v.findViewById(R.id.tvCarouselEmoji)
        val iconContainer: View? = v.findViewById(R.id.iconContainer)
        val ivDelete: ImageView? = v.findViewById(R.id.ivDelete)
        val card: MaterialCardView? = v.findViewById(R.id.cardRoot) ?: v.findViewById(R.id.carouselCard)
    }
}
