package com.mbnetwork.filemanager

import android.graphics.Typeface
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Home: search, storage, categories with counts, recent files, quick tools. */
class HomeFragment : Fragment(), Refreshable {

    private val main get() = requireActivity() as MainActivity
    private lateinit var box: LinearLayout
    private val d get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(10), px(14), px(if (DeviceUi.isLarge(context)) 20 else 120))
        }
        return ScrollView(requireContext()).apply { addView(box); isFillViewport = true }
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun refresh() {
        if (!isAdded) return
        main.supportActionBar?.title = getString(R.string.tab_home)
        main.supportActionBar?.subtitle = null
        box.removeAllViews()
        if (!PermissionHelper.hasStoragePermission(requireContext())) {
            box.addView(TextView(requireContext()).apply {
                setText(R.string.no_permission); gravity = Gravity.CENTER; setPadding(px(20), px(60), px(20), px(20)); textSize = 16f
            })
            box.addView(com.google.android.material.button.MaterialButton(requireContext()).apply {
                setText(R.string.grant_permission)
                setOnClickListener { PermissionHelper.requestIfNeeded(requireActivity()) }
            })
            return
        }
        searchBox()
        section(R.string.storage)
        Trash.volumes(requireContext()).forEachIndexed { i, v ->
            storageCard(if (i == 0) getString(R.string.internal_storage) else getString(R.string.sd_card, v.name), v.absolutePath,
                if (i == 0) R.color.grad_start else R.color.accent)
        }
        section(R.string.categories)
        categories()
        section(R.string.recent_files)
        recent()
        section(R.string.tools)
        tools()
    }

    private fun card(content: View) = MaterialCardView(requireContext()).apply {
        radius = 20 * d; DeviceUi.styleCard(this); cardElevation = if (DeviceUi.liquid(context)) 0f else 2 * d
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, px(4), 0, px(4)) }
        addView(content)
        DeviceUi.applyFocusEffect(this); DeviceUi.liquidTouch(this)
    }

    private fun section(t: Int) = box.addView(TextView(requireContext()).apply {
        setText(t); textSize = 13f; isAllCaps = true; alpha = 0.65f; setTypeface(typeface, Typeface.BOLD)
        setPadding(px(6), px(18), px(6), px(6))
    })

    private fun tile(ic: Int, color: Int, size: Int) = ImageView(requireContext()).apply {
        setImageResource(ic)
        background = DeviceUi.tile(context, ContextCompat.getColor(context, color), 14f)
        androidx.core.widget.ImageViewCompat.setImageTintList(this, android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt()))
        val p = size / 4; setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(size, size)
    }

    private fun searchBox() {
        val input = EditText(requireContext()).apply {
            setHint(R.string.search_files); setSingleLine(); background = null; textSize = 16f
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(px(12), px(14), px(12), px(14))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val go = {
            val q = input.text.toString().trim()
            if (q.isNotEmpty()) main.openCategory(getString(R.string.search_results, q)) { FileIndex.search(it, q) }
        }
        input.setOnEditorActionListener { _, a, e -> if (a == EditorInfo.IME_ACTION_SEARCH || e?.keyCode == KeyEvent.KEYCODE_ENTER) { go(); true } else false }
        box.addView(card(LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(px(14), 0, px(8), 0)
            addView(ImageView(context).apply {
                setImageResource(android.R.drawable.ic_menu_search); alpha = 0.6f
                layoutParams = LinearLayout.LayoutParams(px(24), px(24))
            })
            addView(input)
        }))
    }

    private fun storageCard(title: String, path: String, color: Int) {
        val inner = LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(px(16), px(14), px(16), px(14))
            addView(tile(R.drawable.ic_storage, color, px(46)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(px(14), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply { text = title; textSize = 16f; setTypeface(typeface, Typeface.BOLD) })
                try {
                    val st = android.os.StatFs(path)
                    val total = st.blockCountLong * st.blockSizeLong; val free = st.availableBlocksLong * st.blockSizeLong
                    addView(LinearProgressIndicator(context).apply {
                        progress = if (total > 0) ((total - free) * 100 / total).toInt() else 0
                        trackCornerRadius = px(3); trackThickness = px(6)
                        setIndicatorColor(ContextCompat.getColor(context, color)); setPadding(0, px(6), 0, px(4))
                    })
                    addView(TextView(context).apply {
                        text = getString(R.string.free_of, Formatter.formatShortFileSize(context, free), Formatter.formatShortFileSize(context, total))
                        textSize = 12f; alpha = 0.7f
                    })
                } catch (e: Exception) { }
            })
        }
        box.addView(card(inner).apply { setOnClickListener { main.openLocation(path) } })
    }

    private fun categories() {
        val cols = if (DeviceUi.isLarge(requireContext()) || resources.configuration.screenWidthDp > 560) 8 else 4
        val grid = GridLayout(requireContext()).apply { columnCount = cols }
        val ctx = requireContext().applicationContext
        FileIndex.categories.forEach { c ->
            val count = TextView(requireContext()).apply { text = "…"; textSize = 11f; alpha = 0.65f; gravity = Gravity.CENTER }
            val cell = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setBackgroundResource(R.drawable.bg_icon_item); isFocusable = true; isClickable = true
                setPadding(px(4), px(10), px(4), px(10))
                addView(tile(c.icon, c.color, px(52)))
                addView(TextView(context).apply { setText(c.title); textSize = 12.5f; gravity = Gravity.CENTER; setPadding(0, px(6), 0, 0); maxLines = 1 })
                addView(count)
                setOnClickListener { main.openCategory(getString(c.title)) { FileIndex.list(it, c.key) } }
                DeviceUi.applyFocusEffect(this); DeviceUi.liquidTouch(this)
                layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 }
            }
            grid.addView(cell)
            viewLifecycleOwner.lifecycleScope.launch {
                val n = withContext(Dispatchers.IO) { runCatching { FileIndex.count(ctx, c.key) }.getOrDefault(0) }
                count.text = n.toString()
            }
        }
        box.addView(card(grid.apply { setPadding(px(6), px(8), px(6), px(8)) }).apply { isFocusable = false; isClickable = false; setOnTouchListener(null) })
    }

    private fun recent() {
        val row = LinearLayout(requireContext()).apply { setPadding(px(6), px(10), px(6), px(10)) }
        box.addView(card(HorizontalScrollView(requireContext()).apply { isHorizontalScrollBarEnabled = false; addView(row) })
            .apply { isFocusable = false; isClickable = false; setOnTouchListener(null) })
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { FileIndex.recent(ctx) }
            if (items.isEmpty()) row.addView(TextView(requireContext()).apply { setText(R.string.nothing_recent); alpha = 0.6f; setPadding(px(10), px(10), px(10), px(10)) })
            items.forEach { f ->
                val (ic, col) = FileAdapter.typeOf(f)
                val img = tile(ic, col, px(76))
                if (ThumbLoader.canThumb(f.name)) ThumbLoader.load(requireContext(), f.path, img) {
                    androidx.core.widget.ImageViewCompat.setImageTintList(img, null); img.setPadding(0, 0, 0, 0)
                    img.scaleType = ImageView.ScaleType.CENTER_CROP
                }
                img.clipToOutline = true
                row.addView(LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    setBackgroundResource(R.drawable.bg_icon_item); isFocusable = true; isClickable = true
                    setPadding(px(6), px(6), px(6), px(6))
                    layoutParams = LinearLayout.LayoutParams(px(96), LinearLayout.LayoutParams.WRAP_CONTENT)
                    addView(img)
                    addView(TextView(context).apply { text = f.name; textSize = 11f; maxLines = 2; gravity = Gravity.CENTER; setPadding(0, px(4), 0, 0); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
                    setOnClickListener { main.openLocation(File(f.path).parent ?: return@setOnClickListener) }
                    DeviceUi.applyFocusEffect(this)
                })
            }
        }
    }

    private fun tools() {
        val row = LinearLayout(requireContext()).apply { setPadding(px(6), px(8), px(6), px(8)) }
        fun tool(t: Int, ic: Int, col: Int, act: () -> Unit) = row.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundResource(R.drawable.bg_icon_item); isFocusable = true; isClickable = true
            setPadding(px(4), px(10), px(4), px(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(tile(ic, col, px(48)))
            addView(TextView(context).apply { setText(t); textSize = 12.5f; gravity = Gravity.CENTER; setPadding(0, px(6), 0, 0) })
            setOnClickListener { act() }
            DeviceUi.applyFocusEffect(this); DeviceUi.liquidTouch(this)
        })
        tool(R.string.tab_apps, R.drawable.ic_apps_white, R.color.t_apk) { main.selectTab(R.id.nav_apps) }
        tool(R.string.tab_trash, R.drawable.ic_delete, R.color.t_file) { main.selectTab(R.id.nav_trash) }
        tool(R.string.appearance, R.drawable.ic_tune, R.color.grad_mid) { DeviceUi.chooseTheme(requireActivity(), main.prefs) }
        tool(R.string.check_updates, R.drawable.ic_open, R.color.accent) { main.checkUpdates() }
        box.addView(card(row).apply { isFocusable = false; isClickable = false; setOnTouchListener(null) })
    }
}
