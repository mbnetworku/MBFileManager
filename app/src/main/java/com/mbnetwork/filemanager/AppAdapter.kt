package com.mbnetwork.filemanager

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class AppItem(
    val pkg: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    val enabled: Boolean,
    val installed: Boolean,
    val sourceDir: String,
    val version: String
)

class AppAdapter(private val onClick: (AppItem) -> Unit) : RecyclerView.Adapter<AppAdapter.VH>() {

    var items: List<AppItem> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val label: TextView = v.findViewById(R.id.label)
        val pkg: TextView = v.findViewById(R.id.pkg)
        val badge: TextView = v.findViewById(R.id.badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false).also { DeviceUi.applyFocusEffect(it) })

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val a = items[position]
        val ctx = h.itemView.context
        (h.itemView as? com.google.android.material.card.MaterialCardView)
            ?.setCardBackgroundColor(DeviceUi.glass(ctx, R.color.card_bg))
        h.icon.setImageDrawable(a.icon)
        h.label.text = a.label
        h.pkg.text = "${a.pkg}  •  ${a.version}"
        val (badge, color) = when {
            !a.installed -> ctx.getString(R.string.removed) to R.color.danger
            !a.enabled -> ctx.getString(R.string.disabled) to R.color.badge_off
            a.isSystem -> ctx.getString(R.string.system) to R.color.badge_system
            else -> null to R.color.accent
        }
        androidx.core.view.ViewCompat.setBackgroundTintList(h.badge,
            android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(ctx, color)))
        h.badge.visibility = if (badge == null) View.GONE else View.VISIBLE
        h.badge.text = badge
        h.itemView.alpha = if (a.installed && a.enabled) 1f else 0.55f
        h.itemView.setOnClickListener { onClick(a) }
    }
}
