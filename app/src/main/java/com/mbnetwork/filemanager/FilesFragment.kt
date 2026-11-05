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
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
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

    private data class Clip(val paths: List<String>, val cut: Boolean)
    private var clip: Clip? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = goUp()
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

        // Phone: list. Tablet / TV: grid.
        val cols = DeviceUi.fileColumns(requireContext())
        adapter = FileAdapter(cols > 1, ::onItemClick, ::onItemLongClick)
        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = if (cols > 1) GridLayoutManager(requireContext(), cols) else LinearLayoutManager(requireContext())
            adapter = this@FilesFragment.adapter
        }
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
        main.supportActionBar?.title = getString(R.string.app_name)
        main.supportActionBar?.subtitle = null
        updateHeader()
        backCallback.isEnabled = currentPath != limitPath
        updateFab()

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
    }

    /** Glossy header: storage name, usage bar and tappable breadcrumb chips. */
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
            storageBar.visibility = View.VISIBLE
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
        when {
            f.isDir -> navigate(f.path)
            ApkInstaller.isApk(f.name) -> installApk(f)
            MusicPlayer.isAudio(f.name) -> playMusic(f)
            TextEditorActivity.isText(f.name) -> openText(f, edit = false)
            else -> openFile(f, chooser = false)
        }
    }

    private fun onItemLongClick(f: FileItem, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        val m = popup.menu
        if (ApkInstaller.isApk(f.name)) {
            m.add(0, 8, 0, R.string.install)
            if (main.rootActive) m.add(0, 9, 0, R.string.install_root)
        }
        if (!f.isDir) {
            if (MusicPlayer.isAudio(f.name)) m.add(0, 10, 0, R.string.play_music)
            m.add(0, 11, 0, R.string.open_text)
            m.add(0, 1, 0, R.string.open_with); m.add(0, 7, 0, R.string.share)
        }
        m.add(0, 2, 0, R.string.rename)
        m.add(0, 3, 0, R.string.copy)
        m.add(0, 4, 0, R.string.cut)
        m.add(0, 5, 0, R.string.delete)
        m.add(0, 6, 0, R.string.details)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> openFile(f, chooser = true)
                2 -> promptName(R.string.rename, f.name) { n -> ops.rename(f.path, n) }
                3 -> { clip = Clip(listOf(f.path), false); updateFab(); toast(R.string.paste) }
                4 -> { clip = Clip(listOf(f.path), true); updateFab(); toast(R.string.paste) }
                5 -> confirmDelete(f)
                6 -> showDetails(f)
                7 -> share(f)
                8 -> installApk(f)
                10 -> playMusic(f)
                11 -> openText(f, edit = false)
                9 -> runOp {
                    val r = ApkInstaller.installSilently(f.path)
                    if (!r.ok || r.out.contains("Failure")) throw java.io.IOException((r.err + r.out).trim())
                }
            }
            true
        }
        popup.show()
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
        menu.findItem(R.id.action_root).isVisible = RootShell.deviceRooted  // hidden on non-rooted devices
        menu.findItem(R.id.action_root).isChecked = main.rootActive
        menu.findItem(R.id.action_hidden).isChecked = prefs.showHidden
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_up -> goUp()
            R.id.action_storage -> chooseStorage()
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
