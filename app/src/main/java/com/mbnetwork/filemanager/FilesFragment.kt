package com.mbnetwork.filemanager

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import android.os.StatFs
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

class FilesFragment : Fragment(R.layout.fragment_files), Refreshable, MenuProvider {

    private val main get() = requireActivity() as MainActivity
    private val prefs get() = main.prefs
    private val ops get() = FileOps(main.rootActive)

    private lateinit var adapter: FileAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var selectBar: View
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var emptyView: View
    private lateinit var emptyText: TextView
    private lateinit var grantButton: Button
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var rootBanner: View
    private lateinit var headerTitle: TextView
    private lateinit var headerSub: TextView
    private lateinit var storageBar: LinearProgressIndicator
    private lateinit var crumbs: LinearLayout
    private lateinit var crumbScroll: HorizontalScrollView

    private val internalRoot: String = Environment.getExternalStorageDirectory().absolutePath
    private var limitPath = internalRoot
    private var currentPath = internalRoot

    /** Recents tab: the category list is the root of the tab (Back does not leave it). */
    private var pinned = false
    private var virtualTitle: String? = null
    private var virtualLoader: ((android.content.Context) -> List<FileItem>)? = null

    /** Shows a category or search result list (from Home). Back returns to the folder. */
    fun openCategory(title: String, loader: (android.content.Context) -> List<FileItem>) {
        if (adapter.selectionMode) exitSelect()
        virtualTitle = title; virtualLoader = loader
        refresh()
    }

    private fun closeCategory() { virtualTitle = null; virtualLoader = null; refresh() }

    private data class Clip(val paths: List<String>, val cut: Boolean)
    private var clip: Clip? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = when {
            adapter.selectionMode -> exitSelect()
            virtualTitle != null && !pinned ->
                if (parentFragmentManager.backStackEntryCount > 0) parentFragmentManager.popBackStack() else closeCategory()
            else -> goUp()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        swipe = view.findViewById(R.id.swipe)
        emptyView = view.findViewById(R.id.emptyView)
        emptyText = view.findViewById(R.id.emptyText)
        grantButton = view.findViewById(R.id.grantButton)
        fab = view.findViewById(R.id.fab)
        rootBanner = view.findViewById(R.id.rootChip)
        headerTitle = view.findViewById(R.id.headerTitle)
        headerSub = view.findViewById(R.id.headerSub)
        storageBar = view.findViewById(R.id.storageBar)
        crumbs = view.findViewById(R.id.crumbs)
        crumbScroll = view.findViewById(R.id.crumbScroll)

        recycler = view.findViewById(R.id.list)
        recycler.layoutAnimation = android.view.animation.AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_anim)
        selectBar = view.findViewById(R.id.selectBar)
        setupList()
        // phones: the list scrolls UNDER the glass top bar (start it just below the bar)
        if (main.topInset > 0) {
            val dd0 = resources.displayMetrics.density
            recycler.setPadding(recycler.paddingLeft, main.topInset + (6 * dd0).toInt(), recycler.paddingRight, recycler.paddingBottom)
            swipe.setProgressViewOffset(false, main.topInset, main.topInset + (64 * dd0).toInt())
        }
        // multi-select bar = Liquid Glass; icons in iOS blue
        (selectBar as GlassView).setup(swipe, radiusDp = 26f, tintAlpha = 0.66f)
        val selTint = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.brand))
        listOf(R.id.selShare, R.id.selCopy, R.id.selMove, R.id.selZip, R.id.selDelete, R.id.selMore).forEach {
            androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(view.findViewById<TextView>(it), selTint)
        }
        // The storage card is not shown in Files (it lives on the Home screen)
        view.findViewById<View>(R.id.appBar).visibility = View.GONE
        // Landscape / short screens: compact header (no usage bar)
        if (resources.configuration.screenHeightDp < 480) {
            storageBar.visibility = View.GONE; headerSub.visibility = View.GONE
            view.findViewById<View>(R.id.header).setPadding((16 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt(), (16 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt())
        }
        if (!DeviceUi.isLarge(requireContext())) {
            val dd = resources.displayMetrics.density
            (fab.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = main.bottomInset + (8 * dd).toInt()
            (selectBar.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = (14 * dd).toInt()
            recycler.setPadding(recycler.paddingLeft, recycler.paddingTop, recycler.paddingRight, main.bottomInset + (84 * dd).toInt())
        }
        view.findViewById<View>(R.id.selShare).setOnClickListener { shareMany(adapter.selectedItems()) }
        view.findViewById<View>(R.id.selCopy).setOnClickListener { setClip(adapter.selectedItems(), false) }
        view.findViewById<View>(R.id.selMove).setOnClickListener { setClip(adapter.selectedItems(), true) }
        view.findViewById<View>(R.id.selZip).setOnClickListener { zipItems(adapter.selectedItems()) }
        view.findViewById<View>(R.id.selDelete).setOnClickListener { confirmDeleteMany(adapter.selectedItems()) }
        view.findViewById<View>(R.id.selMore).setOnClickListener { adapter.selectedItems().takeIf { it.isNotEmpty() }?.let { showSheet(it) } }
        swipe.setOnRefreshListener { refresh() }
        grantButton.setOnClickListener { PermissionHelper.requestIfNeeded(requireActivity()) }
        fab.setOnClickListener {
            if (clip != null) paste()
            else MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.create_new)
                .setItems(arrayOf(getString(R.string.new_folder), getString(R.string.new_text_file))) { _, i ->
                    if (i == 0) promptName(R.string.new_folder) { ops.mkdir(currentPath, it) } else newTextFile()
                }.show()
        }
        fab.setOnLongClickListener { if (clip != null) { clip = null; updateFab(); toast(R.string.cancel_paste) }; true }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        main.pendingLocation?.let { main.pendingLocation = null; view.post { openLocation(it) } }
        main.pendingCategory?.let { (t, l) -> main.pendingCategory = null; virtualTitle = t; virtualLoader = l }
        pinned = main.pendingPinned; main.pendingPinned = false
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()
        refresh() // also picks up a permission granted in Settings
    }

    override fun refresh() {
        if (!isAdded) return
        rootBanner.visibility = if (main.rootActive) View.VISIBLE else View.GONE
        if (!main.rootActive && limitPath == "/") { limitPath = internalRoot; currentPath = internalRoot }
        main.supportActionBar?.title = when {
            adapter.selectionMode -> selectionTitle()
            virtualTitle != null -> virtualTitle
            currentPath == internalRoot -> getString(R.string.internal_storage)
            currentPath == limitPath -> StorageVolumes.list(requireContext()).firstOrNull { it.path == currentPath }?.label ?: File(currentPath).name
            else -> File(currentPath).name.ifEmpty { "/" }
        }
        main.supportActionBar?.subtitle = if (virtualTitle != null || currentPath == limitPath) null
            else File(currentPath).parent?.replace(internalRoot, getString(R.string.internal_storage))
        updateHeader()
        backCallback.isEnabled = currentPath != limitPath || adapter.selectionMode || (virtualTitle != null && !pinned)
        updateNav()
        updateFab()
        virtualLoader?.let { loader ->
            swipe.isRefreshing = true
            val app = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch {
                val list = withContext(Dispatchers.IO) { runCatching { loader(app) }.getOrDefault(emptyList()) }
                swipe.isRefreshing = false
                animateList("v|" + virtualTitle)
                adapter.items = list
                if (list.isEmpty()) showEmpty(getString(R.string.empty_folder), false) else emptyView.visibility = View.GONE
            }
            return
        }

        // Without root and without storage permission there is nothing to show.
        if (!main.rootActive && !PermissionHelper.hasStoragePermission(requireContext())) {
            adapter.items = emptyList()
            showEmpty(getString(R.string.no_permission), showGrant = true)
            grantButton.setText(R.string.grant_permission)
            grantButton.setOnClickListener { PermissionHelper.requestIfNeeded(requireActivity()) }
            swipe.isRefreshing = false
            return
        }
        swipe.isRefreshing = true
        val path = currentPath
        val o = ops
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { sort(o.list(path)) } }
            swipe.isRefreshing = false
            result.onSuccess {
                animateList("p|" + path)
                adapter.items = it
                if (it.isEmpty()) showEmpty(getString(R.string.empty_folder), false) else emptyView.visibility = View.GONE
            }.onFailure {
                adapter.items = emptyList()
                if (path.contains("/Android/obb") || path.contains("/Android/data")) {
                    showEmpty(getString(R.string.obb_hint), showGrant = true)
                    grantButton.setText(R.string.allow_access)
                    grantButton.setOnClickListener {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:${requireContext().packageName}")))
                    }
                } else showEmpty(getString(R.string.failed, it.message ?: ""), false)
            }
        }
    }

    private fun sort(list: List<FileItem>): List<FileItem> {
        val visible = (if (prefs.showHidden) list else list.filterNot { it.isHidden }).filterNot { Trash.isTrashDir(it.name) }
        val cmp: Comparator<FileItem> = when (prefs.sort) {
            1 -> compareByDescending { it.size }
            2 -> compareByDescending { it.lastModified }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        return visible.sortedWith(compareByDescending<FileItem> { it.isDir }.then(cmp))
    }

    private fun showEmpty(text: String, showGrant: Boolean) {
        emptyView.visibility = View.VISIBLE
        emptyText.text = text
        grantButton.visibility = if (showGrant) View.VISIBLE else View.GONE
    }

    private fun updateFab() {
        fab.setIconResource(if (clip != null) R.drawable.ic_paste else R.drawable.ic_new_folder)
        fab.text = getString(if (clip != null) R.string.paste else R.string.new_folder)
        fab.visibility = if (adapter.selectionMode || virtualTitle != null) View.GONE else View.VISIBLE
    }

    /** Glossy header: storage name, usage bar and tappable breadcrumb chips. */
    private fun selectionTitle() =
        if (adapter.selected.isEmpty()) getString(R.string.select_items) else getString(R.string.n_selected, adapter.selected.size)

    /** Back arrow when inside a folder / pushed from Browse, sidebar button otherwise (iOS Files header). */
    private fun updateNav() {
        val pushed = parentFragmentManager.backStackEntryCount > 0
        val back = !adapter.selectionMode && (if (virtualTitle != null) !pinned else (pushed || currentPath != limitPath))
        main.setNavMode(back) { requireActivity().onBackPressedDispatcher.onBackPressed() }
    }

    private var lastAnimKey: String? = null

    /** Items rise in one by one when a new folder / list is shown. */
    private fun animateList(key: String) {
        if (key == lastAnimKey || !prefs.animations) return
        lastAnimKey = key
        recycler.scheduleLayoutAnimation()
    }

    private fun showSelectBar(show: Boolean) {
        val dist = 70 * resources.displayMetrics.density
        selectBar.animate().cancel()
        if (!prefs.animations) { selectBar.visibility = if (show) View.VISIBLE else View.GONE; return }
        if (show) {
            if (selectBar.visibility != View.VISIBLE) { selectBar.alpha = 0f; selectBar.translationY = dist; selectBar.visibility = View.VISIBLE }
            selectBar.animate().alpha(1f).translationY(0f).setDuration(240)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        } else if (selectBar.visibility == View.VISIBLE) {
            selectBar.animate().alpha(0f).translationY(dist).setDuration(180).withEndAction {
                selectBar.visibility = View.GONE; selectBar.translationY = 0f; selectBar.alpha = 1f
            }.start()
        }
    }

    private fun updateHeader() {
        headerTitle.text = when {
            limitPath == "/" -> getString(R.string.root_fs)
            limitPath == internalRoot -> getString(R.string.internal_storage)
            else -> File(limitPath).name
        }
        try {
            val st = StatFs(if (limitPath == "/") "/data" else limitPath)
            val total = st.blockCountLong * st.blockSizeLong
            val free = st.availableBlocksLong * st.blockSizeLong
            storageBar.visibility = if (resources.configuration.screenHeightDp < 480) View.GONE else View.VISIBLE
            storageBar.progress = if (total > 0) ((total - free) * 100 / total).toInt() else 0
            headerSub.text = "${Formatter.formatShortFileSize(requireContext(), free)} free of " +
                Formatter.formatShortFileSize(requireContext(), total)
        } catch (e: Exception) {
            storageBar.visibility = View.GONE
            headerSub.text = currentPath
        }

        crumbs.removeAllViews()
        val d = resources.displayMetrics.density
        val parts = mutableListOf(limitPath)
        currentPath.removePrefix(limitPath).split('/').filter { it.isNotEmpty() }
            .forEach { parts += File(parts.last(), it).path }
        parts.forEachIndexed { i, p ->
            if (i > 0) crumbs.addView(ImageView(requireContext()).apply {
                setImageResource(R.drawable.ic_chevron); alpha = 0.8f
                layoutParams = LinearLayout.LayoutParams((18 * d).toInt(), (18 * d).toInt())
            })
            crumbs.addView(TextView(requireContext()).apply {
                text = if (i == 0) (if (p == "/") "/" else headerTitle.text) else File(p).name
                setTextColor(0xFFFFFFFF.toInt()); textSize = 13f
                if (i == parts.size - 1) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setBackgroundResource(R.drawable.bg_chip)
                setPadding((12 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt())
                gravity = Gravity.CENTER
                isFocusable = true; isClickable = true
                setOnClickListener { navigate(p) }
            })
        }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun navigate(path: String) { currentPath = path; refresh() }

    private fun goUp() {
        if (currentPath == limitPath) return
        navigate(File(currentPath).parent ?: limitPath)
    }

    private fun onItemClick(f: FileItem) {
        if (adapter.selectionMode) { adapter.toggle(f); updateSelectionUi(); return }
        when {
            f.isDir -> { virtualTitle = null; virtualLoader = null; limitPath = if (f.path.startsWith(internalRoot)) internalRoot else limitPath; navigate(f.path) }
            ArchiveUtil.isArchive(f.name) -> confirmExtract(f)
            PdfActivity.isPdf(f.name) && File(f.path).canRead() -> openPdf(f)
            OfficeDoc.isOffice(f.name) && OfficeDoc.kind(f.name) != "old" -> openOffice(f)
            ApkInstaller.isApk(f.name) -> installApk(f)
            MediaTypes.isImage(f.name) && File(f.path).canRead() -> openPhoto(f)
            MediaTypes.isVideo(f.name) && File(f.path).canRead() -> openVideo(f)
            MusicPlayer.isAudio(f.name) -> playMusic(f)
            TextEditorActivity.isText(f.name) -> openText(f, edit = false)
            else -> openFile(f, chooser = false)
        }
    }

    /** Long-press: in Select mode toggles the item, otherwise opens the action sheet for it. */
    private fun onItemLongClick(f: FileItem, anchor: View) {
        if (adapter.selectionMode) { adapter.toggle(f); updateSelectionUi() } else showSheet(listOf(f))
    }

    // ---------- view modes & selection ----------
    private fun setupList() {
        val ctx = requireContext()
        val large = DeviceUi.isLarge(ctx)
        val mode = when {
            prefs.viewMode == 0 -> FileAdapter.ICONS
            large -> FileAdapter.GRID
            else -> FileAdapter.LIST
        }
        val old = if (::adapter.isInitialized) adapter else null
        adapter = FileAdapter(mode, ::onItemClick, ::onItemLongClick)
        adapter.glassColor = DeviceUi.glass(ctx, R.color.card_bg)
        old?.let { adapter.items = it.items }
        val cols = when (mode) {
            FileAdapter.ICONS -> (if (prefs.iconColumns > 0) DeviceUi.scaledColumns(ctx, prefs.iconColumns) else DeviceUi.iconColumns(ctx))
                .coerceIn(3, DeviceUi.maxIconColumns(ctx))
            FileAdapter.GRID -> DeviceUi.fileColumns(ctx)
            else -> 1
        }
        recycler.layoutManager = if (cols > 1) GridLayoutManager(ctx, cols) else LinearLayoutManager(ctx)
        adapter.columns = cols
        adapter.animateChecks = prefs.animations
        recycler.adapter = adapter
        if (mode == FileAdapter.ICONS) enablePinch()
    }

    /** Pinch the list: spread = bigger thumbnails (fewer columns), pinch = smaller (more columns). */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun enablePinch() {
        var acc = 1f
        val det = android.view.ScaleGestureDetector(requireContext(), object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: android.view.ScaleGestureDetector): Boolean { acc = 1f; return true }
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                acc *= d.scaleFactor
                val lm = recycler.layoutManager as? GridLayoutManager ?: return true
                val now = lm.spanCount
                val next = when { acc > 1.25f -> now - 1; acc < 0.8f -> now + 1; else -> now }
                    .coerceIn(3, DeviceUi.maxIconColumns(requireContext()))
                if (next != now) {
                    lm.spanCount = next; adapter.columns = next; adapter.notifyDataSetChanged(); acc = 1f
                    val c = resources.configuration
                    val ratio = minOf(c.screenWidthDp, c.screenHeightDp).toFloat() / c.screenWidthDp.coerceAtLeast(1)
                    prefs.iconColumns = Math.round(next * ratio).coerceAtLeast(2)
                }
                return true
            }
        })
        recycler.setOnTouchListener { _, e -> det.onTouchEvent(e); false }
    }

    private fun chooseColumns() {
        val c = resources.configuration
        val portraitMax = maxOf(3, minOf(c.screenWidthDp, c.screenHeightDp) / 64)
        val opts = listOf(0) + (3..portraitMax.coerceAtMost(8)).toList()
        val names = opts.map { if (it == 0) getString(R.string.columns_auto) else "$it" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.columns)
            .setSingleChoiceItems(names, opts.indexOf(prefs.iconColumns).coerceAtLeast(0)) { dlg, i ->
                prefs.iconColumns = opts[i]
                if (prefs.viewMode != 0) prefs.viewMode = 0 // columns apply to the thumbnail view
                setupList(); requireActivity().invalidateOptionsMenu(); dlg.dismiss()
            }
            .setMessage(null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        toast(R.string.columns_hint)
    }

    private fun enterSelect(first: FileItem? = null) {
        adapter.selectionMode = true
        first?.let { adapter.toggle(it) }
        updateSelectionUi()
    }

    private fun exitSelect() {
        adapter.selectionMode = false
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        val on = adapter.selectionMode
        showSelectBar(on)
        fab.visibility = if (on || virtualTitle != null) View.GONE else View.VISIBLE
        main.setTabBarVisible(!on)                     // the batch toolbar replaces the tab bar
        updateNav()
        backCallback.isEnabled = on || (virtualTitle != null && !pinned) || currentPath != limitPath
        main.supportActionBar?.title = if (on) selectionTitle()
            else virtualTitle ?: File(currentPath).name.ifEmpty { "/" }
        listOf(R.id.selShare, R.id.selCopy, R.id.selMove, R.id.selZip, R.id.selDelete, R.id.selMore).forEach {
            selectBar.findViewById<View>(it).alpha = if (adapter.selected.isEmpty()) 0.4f else 1f
            selectBar.findViewById<View>(it).isEnabled = adapter.selected.isNotEmpty()
        }
        requireActivity().invalidateOptionsMenu()
    }

    private fun setClip(items: List<FileItem>, cut: Boolean) {
        if (items.isEmpty()) return
        clip = Clip(items.map { it.path }, cut)
        exitSelect(); updateFab()
        Toast.makeText(requireContext(), R.string.paste, Toast.LENGTH_SHORT).show()
    }

    // ---------- zip ----------
    private fun zipItems(items: List<FileItem>) {
        if (items.isEmpty()) return
        val base = if (items.size == 1) items[0].name.substringBeforeLast('.').ifEmpty { items[0].name } else "Archive"
        val dir = currentPath
        val formats = arrayOf("ZIP (.zip)", "ZIP + password (.zip)", "TAR (.tar)", "TAR + GZIP (.tar.gz)")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.archive_format)
            .setItems(formats) { _, pick ->
                val withPw = pick == 1
                val which = when (pick) { 0, 1 -> ArchiveUtil.ZIP; 2 -> ArchiveUtil.TAR; else -> ArchiveUtil.TGZ }
                val ext = ArchiveUtil.ext(which)
                PasswordDialog.nameAndPassword(requireContext(), R.string.zip_name, "$base$ext", withPw) { raw, pw ->
                    val name = if (raw.lowercase().endsWith(ext)) raw.dropLast(ext.length) else raw
                    runOp { ArchiveUtil.create(items.map { File(it.path) }, ZipUtil.uniqueName(File(dir), name, ext), which, if (withPw) pw else null) }
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
        exitSelect()
    }

    private fun confirmExtract(f: FileItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.extract)
            .setMessage(getString(R.string.extract_confirm, f.name))
            .setPositiveButton(R.string.extract) { _, _ -> extract(f) }
            .setNeutralButton(R.string.open_with) { _, _ -> openFile(f, chooser = true) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun extract(f: FileItem, password: String? = null, wrong: Boolean = false) {
        val parent = File(f.path).parentFile ?: return
        swipe.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                val dest = ZipUtil.uniqueName(parent, ArchiveUtil.baseName(f.name), "")
                try { ArchiveUtil.extract(File(f.path), dest, password); null }
                catch (e: Throwable) { dest.deleteRecursively(); e }
            }
            when (err) {
                null -> { toast(R.string.done); refresh() }
                is ArchiveUtil.NeedPassword, is ArchiveUtil.WrongPassword -> {
                    refresh()
                    PasswordDialog.ask(requireContext(), f.name, err is ArchiveUtil.WrongPassword) { extract(f, it) }
                }
                else -> { refresh(); Toast.makeText(requireContext(), getString(R.string.failed, err.message ?: ""), Toast.LENGTH_LONG).show() }
            }
        }
    }

    // ---------- share many ----------
    private fun shareIntent(items: List<FileItem>): Intent? {
        val files = items.filter { !it.isDir }
        if (files.isEmpty()) return null
        val auth = "${requireContext().packageName}.fileprovider"
        val uris = ArrayList(files.map { FileProvider.getUriForFile(requireContext(), auth, File(it.path)) })
        val types = files.map { mime(it.name) }.distinct()
        val type = if (types.size == 1) types[0] else "*/*"
        return (if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris))
            .setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun shareMany(items: List<FileItem>) {
        val i = shareIntent(items) ?: return
        try { startActivity(Intent.createChooser(i, getString(R.string.share))) } catch (e: Exception) { toast(R.string.no_app_to_open) }
    }

    // ---------- action sheet (bottom panel) ----------
    private fun showSheet(items: List<FileItem>) {
        val ctx = requireContext()
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx, R.style.ThemeOverlay_MBFileManager_Sheet)
        val v = layoutInflater.inflate(R.layout.sheet_actions, null)
        // Liquid Glass sheet: blurs the screen behind it
        (v as GlassView).setup(requireActivity().window.decorView, radiusDp = 32f, shape = GlassView.SHAPE_TOP, tintAlpha = 0.74f)
        sheet.setContentView(v)
        val single = items.singleOrNull()
        val d = resources.displayMetrics.density

        // header
        val icon = v.findViewById<ImageView>(R.id.sheetIcon)
        icon.background = null
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        icon.setImageDrawable(FileIcons.drawable(if (items.size == 1) items[0] else FileItem("", "", true, 0, 0)))
        if (items.size == 1 && ThumbLoader.canThumb(items[0].name)) ThumbLoader.load(ctx, items[0].path, icon) {
            icon.scaleType = ImageView.ScaleType.CENTER_CROP
            icon.setBackgroundResource(R.drawable.bg_thumb_win)
            icon.clipToOutline = true
        }
        v.findViewById<TextView>(R.id.sheetTitle).text = single?.name ?: getString(R.string.n_items, items.size)
        val sub = v.findViewById<TextView>(R.id.sheetSub)
        sub.setText(R.string.calculating)
        viewLifecycleOwner.lifecycleScope.launch {
            val total = withContext(Dispatchers.IO) { items.sumOf { if (it.isDir) File(it.path).walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } else it.size } }
            sub.text = Formatter.formatShortFileSize(ctx, total) + if (single != null) "  •  " + (single.path.substringBeforeLast('/')) else ""
        }
        v.findViewById<View>(R.id.sheetClose).setOnClickListener { sheet.dismiss() }

        // apps row (share targets)
        val row = v.findViewById<LinearLayout>(R.id.appsRow)
        val share = shareIntent(items)
        if (share == null) v.findViewById<View>(R.id.appsScroll).visibility = View.GONE else {
            @Suppress("DEPRECATION")
            val targets = ctx.packageManager.queryIntentActivities(share, 0).take(12)
            targets.forEach { ri ->
                row.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    setBackgroundResource(R.drawable.bg_app_target); isFocusable = true; isClickable = true
                    setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
                    layoutParams = LinearLayout.LayoutParams((76 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                    addView(ImageView(ctx).apply {
                        setImageDrawable(ri.loadIcon(ctx.packageManager))
                        layoutParams = LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt())
                    })
                    addView(TextView(ctx).apply {
                        text = ri.loadLabel(ctx.packageManager); textSize = 11f; maxLines = 2
                        gravity = Gravity.CENTER; setPadding(0, (4 * d).toInt(), 0, 0)
                    })
                    setOnClickListener {
                        sheet.dismiss()
                        try { startActivity(Intent(share).setClassName(ri.activityInfo.packageName, ri.activityInfo.name)) }
                        catch (e: Exception) { toast(R.string.no_app_to_open) }
                    }
                })
            }
        }

        // action rows
        fun addRow(group: LinearLayout, text: Int, iconRes: Int, danger: Boolean = false, act: () -> Unit) {
            if (group.childCount > 0) group.addView(View(ctx).apply {
                setBackgroundColor(ContextCompat.getColor(ctx, R.color.row_divider))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { marginStart = (16 * d).toInt() }
            })
            val color = if (danger) ContextCompat.getColor(ctx, R.color.danger) else ContextCompat.getColor(ctx, R.color.text_secondary)
            group.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_app_target); isFocusable = true; isClickable = true
                minimumHeight = (52 * d).toInt()
                setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
                addView(TextView(ctx).apply {
                    setText(text); textSize = 16f
                    if (danger) setTextColor(color)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(ImageView(ctx).apply {
                    setImageResource(iconRes)
                    androidx.core.widget.ImageViewCompat.setImageTintList(this, android.content.res.ColorStateList.valueOf(color))
                    layoutParams = LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt())
                })
                setOnClickListener { sheet.dismiss(); act() }
            })
        }
        val g1 = v.findViewById<LinearLayout>(R.id.group1)
        val g2 = v.findViewById<LinearLayout>(R.id.group2)
        val g3 = v.findViewById<LinearLayout>(R.id.group3)
        if (single != null) {
            val cur = Tags.get(ctx, single.path)
            g1.addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (52 * d).toInt()
                setPadding((16 * d).toInt(), 0, (12 * d).toInt(), 0)
                addView(TextView(ctx).apply {
                    setText(R.string.tags); textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                Tags.all.forEach { t ->
                    addView(View(ctx).apply {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(t.color)
                            if (cur == t.key) setStroke((3 * d).toInt(), ContextCompat.getColor(ctx, R.color.label))
                        }
                        alpha = if (cur == null || cur == t.key) 1f else 0.4f
                        contentDescription = getString(t.label)
                        layoutParams = LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt()).apply { marginStart = (12 * d).toInt() }
                        setOnClickListener {
                            Tags.set(ctx, single.path, if (cur == t.key) null else t.key)
                            sheet.dismiss(); refresh()
                        }
                    })
                }
            })
        }
        if (single != null && !single.isDir) {
            when {
                ApkInstaller.isApk(single.name) -> {
                    addRow(g1, R.string.install, R.drawable.ic_android) { installApk(single) }
                    if (main.rootActive) addRow(g1, R.string.install_root, R.drawable.ic_android) {
                        runOp {
                            val r = ApkInstaller.installSilently(single.path)
                            if (!r.ok || r.out.contains("Failure")) throw java.io.IOException((r.err + r.out).trim())
                        }
                    }
                }
                MediaTypes.isImage(single.name) && File(single.path).canRead() -> {
                    addRow(g1, R.string.view_photo, R.drawable.ic_image) { openPhoto(single) }
                    addRow(g1, R.string.edit_photo, R.drawable.ic_edit) { editPhoto(single) }
                }
                MediaTypes.isVideo(single.name) && File(single.path).canRead() -> {
                    addRow(g1, R.string.play_video, R.drawable.ic_play) { openVideo(single) }
                    addRow(g1, R.string.edit_video, R.drawable.ic_edit) { editVideo(single) }
                }
                MusicPlayer.isAudio(single.name) -> addRow(g1, R.string.play_music, R.drawable.ic_play) { playMusic(single) }
                PdfActivity.isPdf(single.name) && File(single.path).canRead() -> {
                    addRow(g1, R.string.open_pdf, R.drawable.ic_doc) { openPdf(single) }
                    addRow(g1, R.string.edit, R.drawable.ic_edit) { openPdf(single, edit = true) }
                }
                OfficeDoc.isOffice(single.name) && OfficeDoc.kind(single.name) != "old" -> {
                    addRow(g1, R.string.open_office, R.drawable.ic_doc) { openOffice(single) }
                    if (OfficeDoc.kind(single.name) == "docx")
                        addRow(g1, R.string.convert_pdf, R.drawable.ic_doc) { OfficeUnlock.open(requireActivity() as androidx.appcompat.app.AppCompatActivity, File(single.path)) { DocxToPdf.start(requireActivity() as androidx.appcompat.app.AppCompatActivity, it) } }
                }
                ArchiveUtil.isArchive(single.name) -> addRow(g1, R.string.extract, R.drawable.ic_zip) { extract(single) }
            }
            addRow(g1, R.string.open_with, R.drawable.ic_open) { openFile(single, chooser = true) }
            addRow(g1, R.string.open_text, R.drawable.ic_edit) { openText(single, edit = false) }
        }
        addRow(g2, R.string.copy, R.drawable.ic_copy) { setClip(items, false) }
        addRow(g2, R.string.move, R.drawable.ic_move) { setClip(items, true) }
        addRow(g2, R.string.compress, R.drawable.ic_zip) { zipItems(items) }
        if (single != null) {
            addRow(g2, R.string.rename, R.drawable.ic_edit) { promptName(R.string.rename, single.name) { n -> ops.rename(single.path, n) } }
            addRow(g2, R.string.details, R.drawable.ic_info) { showDetails(single) }
        }
        if (!adapter.selectionMode) addRow(g3, R.string.select, R.drawable.ic_check_circle) { enterSelect(single) }
        addRow(g3, R.string.delete, R.drawable.ic_delete, danger = true) { confirmDeleteMany(items) }
        listOf(g1, g2, g3).forEach {
            it.visibility = if (it.childCount == 0) View.GONE else View.VISIBLE
            it.background?.mutate()?.alpha = (255 * (100 - prefs.transparency) / 100).coerceIn(215, 255)
        }

        // Android 12+: blur what's behind the panel
        if (android.os.Build.VERSION.SDK_INT >= 31) sheet.window?.let {
            it.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            it.attributes = it.attributes.apply { blurBehindRadius = 40 }
        }
        sheet.show()
    }

    private fun confirmDeleteMany(items: List<FileItem>) {
        if (items.isEmpty()) return
        if (items.size == 1) { confirmDelete(items[0]); exitSelect(); return }
        val ctx = requireContext().applicationContext
        val canBin = items.all { Trash.canTrash(ctx, it.path) && File(it.path).canWrite() }
        val b = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.delete)
            .setMessage(getString(R.string.n_items, items.size))
        if (canBin) {
            b.setPositiveButton(R.string.move_to_bin) { _, _ -> runOp { items.forEach { Trash.moveToTrash(ctx, it.path) } } }
                .setNeutralButton(R.string.delete_forever) { _, _ -> runOp { items.forEach { ops.delete(it.path) } } }
        } else b.setPositiveButton(R.string.delete_forever) { _, _ -> runOp { items.forEach { ops.delete(it.path) } } }
        b.setNegativeButton(R.string.cancel, null).show()
        exitSelect()
    }

    private fun paste() {
        val c = clip ?: return
        val dest = currentPath
        runOp {
            c.paths.forEach { if (c.cut) ops.move(it, dest) else ops.copy(it, dest) }
        }
        clip = null
        updateFab()
    }

    private fun confirmDelete(f: FileItem) {
        val ctx = requireContext().applicationContext
        val b = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.delete)
        if (Trash.canTrash(ctx, f.path) && java.io.File(f.path).canWrite()) {
            // Default: move to Recycle Bin (restorable). Permanent delete is still available.
            b.setMessage(getString(R.string.delete_bin_confirm, f.name))
                .setPositiveButton(R.string.move_to_bin) { _, _ -> runOp { Trash.moveToTrash(ctx, f.path) } }
                .setNeutralButton(R.string.delete_forever) { _, _ -> runOp { ops.delete(f.path) } }
        } else {
            b.setMessage(getString(R.string.delete_confirm, f.name))
                .setPositiveButton(R.string.delete_forever) { _, _ -> runOp { ops.delete(f.path) } }
        }
        b.setNegativeButton(R.string.cancel, null).show()
    }

    private fun showDetails(f: FileItem) {
        val file = File(f.path)
        val msg = buildString {
            append("Path: ${f.path}\n")
            append("Type: ${if (f.isDir) "Folder" else mime(f.name)}\n")
            if (!f.isDir) append("Size: ${Formatter.formatFileSize(requireContext(), f.size)} (${f.size} bytes)\n")
            if (f.lastModified > 0) append("Modified: ${Date(f.lastModified)}\n")
            append("Readable: ${file.canRead()}  Writable: ${file.canWrite()}")
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle(f.name).setMessage(msg)
            .setPositiveButton(R.string.ok, null).show()
    }

    private fun promptName(title: Int, initial: String = "", action: (String) -> Unit) {
        val input = EditText(requireContext()).apply { setText(initial); setSingleLine(); selectAll() }
        val box = FrameLayout(requireContext()).apply {
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0); addView(input)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title).setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && !name.contains('/')) runOp { action(name) }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** Runs a file operation in the background, reports the result and refreshes the list. */
    private fun runOp(op: () -> Unit) {
        swipe.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(op) }
            r.onSuccess { toast(R.string.done) }
                .onFailure { Toast.makeText(requireContext(), getString(R.string.failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
            refresh()
        }
    }

    private fun mime(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "*/*"

    /** Returns a readable file: the file itself, or (root mode) a temporary copy made via su. */
    private suspend fun readableCopy(f: FileItem): File = withContext(Dispatchers.IO) {
        val file = File(f.path)
        if (file.canRead() || !main.rootActive) return@withContext file
        val tmp = File(requireContext().cacheDir, "root_open/${f.name}")
        tmp.parentFile?.mkdirs()
        RootShell.run("cat ${RootShell.q(f.path)} > ${RootShell.q(tmp.path)} && chmod 666 ${RootShell.q(tmp.path)}")
        tmp
    }

    private fun uriIntent(action: String, file: File, name: String): Intent {
        val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
        return Intent(action).apply {
            if (action == Intent.ACTION_SEND) { type = mime(name); putExtra(Intent.EXTRA_STREAM, uri) }
            else setDataAndType(uri, mime(name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    private fun openFile(f: FileItem, chooser: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val intent = uriIntent(Intent.ACTION_VIEW, readableCopy(f), f.name)
                startActivity(if (chooser) Intent.createChooser(intent, getString(R.string.open_with)) else intent)
            } catch (e: ActivityNotFoundException) {
                toast(R.string.no_app_to_open)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun playMusic(f: FileItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val file = readableCopy(f)
            startActivity(Intent(requireContext(), PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_PATH, file.path))
        }
    }

    private fun openPhoto(f: FileItem) =
        startActivity(Intent(requireContext(), PhotoViewerActivity::class.java).putExtra(PhotoViewerActivity.EXTRA_PATH, f.path))

    private fun editPhoto(f: FileItem) =
        startActivity(Intent(requireContext(), PhotoEditorActivity::class.java).putExtra(PhotoEditorActivity.EXTRA_PATH, f.path))

    private fun openVideo(f: FileItem) =
        startActivity(Intent(requireContext(), VideoPlayerActivity::class.java).putExtra(VideoPlayerActivity.EXTRA_PATH, f.path))

    private fun editVideo(f: FileItem) =
        startActivity(Intent(requireContext(), VideoEditorActivity::class.java).putExtra(VideoEditorActivity.EXTRA_PATH, f.path))

    private fun openPdf(f: FileItem, edit: Boolean = false) =
        startActivity(Intent(requireContext(), PdfActivity::class.java).putExtra(PdfActivity.EXTRA_PATH, f.path).putExtra(PdfActivity.EXTRA_EDIT, edit))

    private fun openOffice(f: FileItem) {
        OfficeUnlock.open(requireActivity() as androidx.appcompat.app.AppCompatActivity, File(f.path)) { ready ->
            startActivity(Intent(requireContext(), OfficeActivity::class.java).putExtra(OfficeActivity.EXTRA_PATH, ready.path))
            refresh()
        }
    }

    private fun openText(f: FileItem, edit: Boolean) {
        startActivity(Intent(requireContext(), TextEditorActivity::class.java)
            .putExtra(TextEditorActivity.EXTRA_PATH, f.path)
            .putExtra(TextEditorActivity.EXTRA_ROOT, main.rootActive)
            .putExtra(TextEditorActivity.EXTRA_EDIT, edit))
    }

    /** Creates a new .txt file and opens it in the built-in notepad. */
    private fun newTextFile() {
        promptName(R.string.new_text_file, "note.txt") { raw ->
            val name = if (raw.contains('.')) raw else "$raw.txt"
            ops.createFile(currentPath, name)
            val path = File(currentPath, name).path
            requireActivity().runOnUiThread { openText(FileItem(name, path, false, 0, 0), edit = true) }
        }
    }

    private fun installApk(f: FileItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                ApkInstaller.install(requireActivity(), readableCopy(f))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun share(f: FileItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                startActivity(Intent.createChooser(uriIntent(Intent.ACTION_SEND, readableCopy(f), f.name), getString(R.string.share)))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- storage chooser ----
    /** Called from the sidebar. */
    fun openLocation(path: String) {
        if (adapter.selectionMode) exitSelect()
        virtualTitle = null; virtualLoader = null; pinned = false
        when {
            path == "/" -> if (main.rootActive) { limitPath = "/"; navigate("/") }
                else main.setRootMode(true) { ok -> if (ok) { limitPath = "/"; navigate("/") } }
            path.startsWith("$internalRoot/") -> { limitPath = path; navigate(path) }
            else -> { limitPath = path; navigate(path) }
        }
    }

    private fun chooseStorage() {
        val names = mutableListOf(getString(R.string.internal_storage))
        val paths = mutableListOf(internalRoot)
        ContextCompat.getExternalFilesDirs(requireContext(), null).drop(1).filterNotNull().forEach {
            val p = it.absolutePath.substringBefore("/Android/data")
            names += getString(R.string.sd_card, File(p).name); paths += p
        }
        names += getString(R.string.obb_folder); paths += "$internalRoot/Android/obb"
        if (RootShell.deviceRooted) {
            names += getString(R.string.data_folder); paths += "$internalRoot/Android/data"
            names += getString(R.string.root_fs); paths += "/"
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.storage)
            .setItems(names.toTypedArray()) { _, i ->
                if (paths[i] == "/" && !main.rootActive) {
                    main.setRootMode(true) { ok -> if (ok) { limitPath = "/"; navigate("/") } }
                } else if (paths[i].startsWith("$internalRoot/Android/")) {
                    limitPath = internalRoot; navigate(paths[i]) // OBB / data: "up" goes back to storage
                } else {
                    limitPath = paths[i]; navigate(paths[i])
                }
            }.show()
    }

    // ---- menu ----
    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) = inflater.inflate(R.menu.files_menu, menu)

    override fun onPrepareMenu(menu: Menu) {
        val sel = adapter.selectionMode
        val all = adapter.items.isNotEmpty() && adapter.selected.size == adapter.items.size
        menu.findItem(R.id.action_select_all).isVisible = sel
        menu.findItem(R.id.action_select_all).setTitle(if (all) R.string.deselect_all else R.string.select_all)
        menu.findItem(R.id.action_done).isVisible = sel
        listOf(R.id.action_select, R.id.action_new_folder, R.id.action_view_icons, R.id.action_view_list, R.id.action_columns,
            R.id.action_sort, R.id.action_hidden, R.id.action_new_file, R.id.action_refresh, R.id.action_theme)
            .forEach { menu.findItem(it).isVisible = !sel }
        menu.findItem(R.id.action_new_folder).isVisible = !sel && virtualTitle == null
        menu.findItem(R.id.action_view_icons).isChecked = prefs.viewMode == 0
        menu.findItem(R.id.action_view_list).isChecked = prefs.viewMode == 1
        menu.findItem(R.id.action_root).isVisible = RootShell.deviceRooted && !sel  // hidden on non-rooted devices
        menu.findItem(R.id.action_root).isChecked = main.rootActive
        menu.findItem(R.id.action_hidden).isChecked = prefs.showHidden
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_select -> enterSelect()
            R.id.action_new_folder -> promptName(R.string.new_folder) { ops.mkdir(currentPath, it) }
            R.id.action_columns -> chooseColumns()
            R.id.action_select_all -> {
                if (adapter.items.isNotEmpty() && adapter.selected.size == adapter.items.size) adapter.clearSelection() else adapter.selectAll()
                updateSelectionUi()
            }
            R.id.action_done -> exitSelect()
            R.id.action_view_icons -> { prefs.viewMode = 0; setupList(); requireActivity().invalidateOptionsMenu() }
            R.id.action_view_list -> { prefs.viewMode = 1; setupList(); requireActivity().invalidateOptionsMenu() }
            R.id.action_root -> main.setRootMode(!main.rootActive) { ok ->
                if (ok) { limitPath = "/" } else { limitPath = internalRoot; currentPath = internalRoot }
                requireActivity().invalidateOptionsMenu(); refresh()
            }
            R.id.action_hidden -> { prefs.showHidden = !prefs.showHidden; item.isChecked = prefs.showHidden; refresh() }
            R.id.action_sort -> MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.sort)
                .setSingleChoiceItems(arrayOf(getString(R.string.sort_name), getString(R.string.sort_size), getString(R.string.sort_date)), prefs.sort) { d, i ->
                    prefs.sort = i; d.dismiss(); refresh()
                }.show()
            R.id.action_new_file -> newTextFile()
            R.id.action_refresh -> refresh()
            R.id.action_theme -> DeviceUi.chooseTheme(requireActivity(), prefs)
            else -> return false
        }
        return true
    }

    private fun toast(res: Int) = Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()
}
