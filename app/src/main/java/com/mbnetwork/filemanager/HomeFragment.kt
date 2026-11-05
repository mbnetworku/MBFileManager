package com.mbnetwork.filemanager

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Browse" – same structure as the iOS Files app:
 * large title, search + filter chips, collapsible Locations / Favorites / Tags lists.
 */
class HomeFragment : Fragment(), Refreshable {

    private val main get() = requireActivity() as MainActivity
    private lateinit var box: LinearLayout
    private val d get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()
    private fun color(res: Int) = ContextCompat.getColor(requireContext(), res)

    companion object {
        /** Which sections are expanded (kept while the app runs). */
        private val open = hashMapOf("loc" to true, "fav" to true, "tag" to true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), main.topInset + px(2), px(16), px(if (DeviceUi.isLarge(context)) 24 else 120))
        }
        return ScrollView(requireContext()).apply { addView(box); isFillViewport = true; clipToPadding = false }
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun refresh() {
        if (!isAdded) return
        main.setNavMode(false)
        main.supportActionBar?.title = ""            // the large title below replaces it
        main.supportActionBar?.subtitle = null
        box.removeAllViews()

        if (!PermissionHelper.hasStoragePermission(requireContext())) {
            box.addView(TextView(requireContext()).apply {
                setText(R.string.no_permission); gravity = Gravity.CENTER
                setPadding(px(20), px(80), px(20), px(20)); textSize = 16f
            })
            box.addView(com.google.android.material.button.MaterialButton(requireContext()).apply {
                setText(R.string.grant_permission)
                setOnClickListener { PermissionHelper.requestIfNeeded(requireActivity()) }
            })
            return
        }

        box.addView(TextView(requireContext()).apply {
            setText(R.string.tab_home); textSize = 34f
            setTypeface(typeface, Typeface.BOLD); setTextColor(color(R.color.label))
            setPadding(px(4), px(2), 0, px(8))
        })
        searchBox()
        chips()
        section("loc", R.string.locations) { locations(it) }
        section("fav", R.string.favorites) { favorites(it) }
        section("tag", R.string.tags) { tags(it) }
    }

    // ------------------------------------------------------------------ search + chips

    private fun searchBox() {
        val input = EditText(requireContext()).apply {
            setHint(R.string.search_files); setSingleLine(); background = null; textSize = 16f
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(px(8), px(10), px(8), px(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val go = {
            val q = input.text.toString().trim()
            if (q.isNotEmpty()) main.openCategory(getString(R.string.search_results, q)) { FileIndex.search(it, q) }
        }
        input.setOnEditorActionListener { _, a, e ->
            if (a == EditorInfo.IME_ACTION_SEARCH || e?.keyCode == KeyEvent.KEYCODE_ENTER) { go(); true } else false
        }
        box.addView(LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_search)
            setPadding(px(12), 0, px(8), 0)
            addView(ImageView(context).apply {
                setImageResource(android.R.drawable.ic_menu_search); alpha = 0.55f
                layoutParams = LinearLayout.LayoutParams(px(20), px(20))
            })
            addView(input)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun chips() {
        val row = LinearLayout(requireContext()).apply { setPadding(0, px(10), 0, px(2)) }
        FileIndex.categories.forEach { c ->
            row.addView(LinearLayout(requireContext()).apply {
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_chip_ios)
                isClickable = true; isFocusable = true
                setPadding(px(12), px(7), px(14), px(7))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = px(8) }
                addView(ImageView(context).apply {
                    setImageResource(c.icon)
                    ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(color(c.color)))
                    layoutParams = LinearLayout.LayoutParams(px(16), px(16)).apply { marginEnd = px(6) }
                })
                addView(TextView(context).apply { setText(c.title); textSize = 13f; setTextColor(color(R.color.label)) })
                setOnClickListener { main.openCategory(getString(c.title)) { FileIndex.list(it, c.key) } }
                DeviceUi.applyFocusEffect(this)
            })
        }
        box.addView(HorizontalScrollView(requireContext()).apply { isHorizontalScrollBarEnabled = false; addView(row) })
    }

    // ------------------------------------------------------------------ sections (collapsible)

    private fun section(key: String, title: Int, build: (LinearLayout) -> Unit) {
        val group = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (open[key] == true) View.VISIBLE else View.GONE
        }
        build(group)

        val chevron = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_chevron_down)
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(color(R.color.brand)))
            rotation = if (open[key] == true) 0f else -90f
            layoutParams = LinearLayout.LayoutParams(px(22), px(22))
        }
        box.addView(LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            setPadding(px(4), px(18), px(6), px(8))
            addView(TextView(context).apply {
                setText(title); textSize = 21f; setTypeface(typeface, Typeface.BOLD); setTextColor(color(R.color.label))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(chevron)
            setOnClickListener {
                val nowOpen = open[key] != true
                open[key] = nowOpen
                group.visibility = if (nowOpen) View.VISIBLE else View.GONE
                chevron.animate().rotation(if (nowOpen) 0f else -90f).setDuration(180).start()
            }
        })
        box.addView(group, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** Every row is a separate rounded card with a small gap (no flat divider lines). */
    private fun addRow(group: LinearLayout, row: View) {
        group.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = px(4); bottomMargin = px(4) })
    }

    private fun iconView(res: Int, tint: Int) = ImageView(requireContext()).apply {
        setImageResource(res)
        ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(tint))
        layoutParams = LinearLayout.LayoutParams(px(28), px(28))
    }

    private fun dotView(c: Int) = View(requireContext()).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
        layoutParams = LinearLayout.LayoutParams(px(16), px(16)).apply { marginStart = px(6); marginEnd = px(6) }
    }

    private fun folderIcon() = ImageView(requireContext()).apply {
        setImageDrawable(FileIcons.drawable(FileItem("x", "x", true, 0, 0)))
        layoutParams = LinearLayout.LayoutParams(px(30), px(30))
    }

    private fun trailingText() = TextView(requireContext()).apply {
        textSize = 15f; setTextColor(color(R.color.text_secondary)); setPadding(0, 0, px(6), 0)
    }

    /** One iOS-style list row: icon, title (+ small subtitle), count, chevron. */
    private fun row(lead: View, title: String, sub: String? = null, trailing: TextView? = null, onClick: () -> Unit): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(54)
            setPadding(px(14), px(6), px(12), px(6))
            isClickable = true; isFocusable = true
            setBackgroundResource(R.drawable.bg_row_round)
            addView(lead)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(14), 0, px(8), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply { text = title; textSize = 16f; setTextColor(color(R.color.label)); maxLines = 1 })
                if (sub != null) addView(TextView(ctx).apply { text = sub; textSize = 12f; setTextColor(color(R.color.text_secondary)); maxLines = 1 })
            })
            trailing?.let { addView(it) }
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_chevron)
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(color(R.color.text_secondary)))
                alpha = 0.7f
                layoutParams = LinearLayout.LayoutParams(px(16), px(16))
            })
            setOnClickListener { onClick() }
            DeviceUi.applyFocusEffect(this)
        }
    }

    private fun freeText(path: String): String? = try {
        val st = android.os.StatFs(path)
        getString(R.string.free_of, Formatter.formatShortFileSize(requireContext(), st.availableBlocksLong * st.blockSizeLong),
            Formatter.formatShortFileSize(requireContext(), st.blockCountLong * st.blockSizeLong))
    } catch (e: Exception) { null }

    // ------------------------------------------------------------------ Locations

    private fun locations(group: LinearLayout) {
        val internal = Environment.getExternalStorageDirectory().absolutePath
        StorageVolumes.list(requireContext()).forEachIndexed { i, v ->
            val icon = when { i == 0 -> R.drawable.ic_phone; v.usb -> R.drawable.ic_usb; else -> R.drawable.ic_sd }
            val tint = if (i == 0) color(R.color.brand) else if (v.usb) color(R.color.t_audio) else color(R.color.brand)
            addRow(group, row(iconView(icon, tint), v.label, freeText(v.path)) { main.openLocation(v.path) })
        }
        addRow(group, row(iconView(R.drawable.ic_usb, color(R.color.t_audio)), getString(R.string.usb_tools)) {
            startActivity(android.content.Intent(requireContext(), UsbToolsActivity::class.java))
        })
        addRow(group, row(iconView(R.drawable.ic_zip, color(R.color.brand)), getString(R.string.obb_folder)) {
            main.openLocation("$internal/Android/obb")
        })
        if (RootShell.deviceRooted) {
            addRow(group, row(iconView(R.drawable.ic_lock, color(R.color.danger)), getString(R.string.root_fs)) { main.openLocation("/") })
        }
        val count = trailingText()
        addRow(group, row(iconView(R.drawable.ic_delete, color(R.color.brand)), getString(R.string.recently_deleted), trailing = count) {
            main.openTrash()
        })
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { runCatching { Trash.list(app).size }.getOrDefault(0) }
            count.text = n.toString()
        }
    }

    // ------------------------------------------------------------------ Favorites

    private fun favorites(group: LinearLayout) {
        val internal = Environment.getExternalStorageDirectory().absolutePath
        listOf(
            R.string.downloads to Environment.DIRECTORY_DOWNLOADS,
            R.string.camera to Environment.DIRECTORY_DCIM,
            R.string.pictures to Environment.DIRECTORY_PICTURES,
            R.string.music to Environment.DIRECTORY_MUSIC,
            R.string.videos to Environment.DIRECTORY_MOVIES,
            R.string.documents to "Documents"
        ).forEach { (name, dir) ->
            val f = File(internal, dir)
            if (!f.exists()) return@forEach
            val count = trailingText()
            addRow(group, row(folderIcon(), getString(name), trailing = count) { main.openLocation(f.absolutePath) })
            viewLifecycleOwner.lifecycleScope.launch {
                val n = withContext(Dispatchers.IO) { f.list()?.size ?: 0 }
                count.text = n.toString()
            }
        }
    }

    // ------------------------------------------------------------------ Tags

    private fun tags(group: LinearLayout) {
        val ctx = requireContext()
        Tags.all.forEach { t ->
            val count = trailingText().apply { text = Tags.count(ctx, t.key).toString() }
            addRow(group, row(dotView(t.color), getString(t.label), trailing = count) {
                main.openCategory(getString(R.string.tag_files, getString(t.label))) { Tags.items(it, t.key) }
            })
        }
    }
}
