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
import com.google.android.material.floatingactionbutton.FloatingActionButton
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
    private lateinit var fab: FloatingActionButton
    private lateinit var rootBanner: View

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
        rootBanner = view.findViewById(R.id.rootBanner)

        adapter = FileAdapter(::onItemClick, ::onItemLongClick)
        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@FilesFragment.adapter
        }
        swipe.setOnRefreshListener { refresh() }
        grantButton.setOnClickListener { PermissionHelper.requestIfNeeded(requireActivity()) }
        fab.setOnClickListener { if (clip != null) paste() else promptName(R.string.new_folder) { ops.mkdir(currentPath, it) } }
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
        main.supportActionBar?.title = if (currentPath == internalRoot) getString(R.string.internal_storage) else File(currentPath).name.ifEmpty { "/" }
        main.supportActionBar?.subtitle = currentPath
        backCallback.isEnabled = currentPath != limitPath
        updateFab()

        // Without root and without storage permission there is nothing to show.
        if (!main.rootActive && !PermissionHelper.hasStoragePermission(requireContext())) {
            adapter.items = emptyList()
            showEmpty(getString(R.string.no_permission), showGrant = true)
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
                showEmpty(getString(R.string.failed, it.message ?: ""), false)
            }
        }
    }

    private fun sort(list: List<FileItem>): List<FileItem> {
        val visible = if (prefs.showHidden) list else list.filterNot { it.isHidden }
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
        fab.setImageResource(if (clip != null) R.drawable.ic_paste else R.drawable.ic_add)
        fab.contentDescription = getString(if (clip != null) R.string.paste else R.string.new_folder)
    }

    private fun navigate(path: String) { currentPath = path; refresh() }

    private fun goUp() {
        if (currentPath == limitPath) return
        navigate(File(currentPath).parent ?: limitPath)
    }

    private fun onItemClick(f: FileItem) {
        if (f.isDir) navigate(f.path) else openFile(f, chooser = false)
    }

    private fun onItemLongClick(f: FileItem, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        val m = popup.menu
        if (!f.isDir) { m.add(0, 1, 0, R.string.open_with); m.add(0, 7, 0, R.string.share) }
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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.delete_confirm, f.name))
            .setPositiveButton(R.string.delete) { _, _ -> runOp { ops.delete(f.path) } }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        names += getString(R.string.root_fs); paths += "/"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.storage)
            .setItems(names.toTypedArray()) { _, i ->
                if (paths[i] == "/" && !main.rootActive) {
                    main.setRootMode(true) { ok -> if (ok) { limitPath = "/"; navigate("/") } }
                } else {
                    limitPath = paths[i]; navigate(paths[i])
                }
            }.show()
    }

    // ---- menu ----
    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) = inflater.inflate(R.menu.files_menu, menu)

    override fun onPrepareMenu(menu: Menu) {
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
            R.id.action_new_file -> promptName(R.string.new_file) { ops.createFile(currentPath, it) }
            R.id.action_refresh -> refresh()
            else -> return false
        }
        return true
    }

    private fun toast(res: Int) = Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()
}
