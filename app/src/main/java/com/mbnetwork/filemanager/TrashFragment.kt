package com.mbnetwork.filemanager

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows files deleted in this app; restore them or delete forever. */
class TrashFragment : Fragment(R.layout.fragment_trash), Refreshable, MenuProvider {

    private val main get() = requireActivity() as MainActivity
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var empty: TextView
    private lateinit var adapter: FileAdapter
    private var entries: List<Trash.Entry> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        swipe = view.findViewById(R.id.swipe)
        empty = view.findViewById(R.id.emptyText)
        val cols = DeviceUi.fileColumns(requireContext())
        adapter = FileAdapter(if (cols > 1) FileAdapter.GRID else FileAdapter.LIST, { onEntry(it) }, { f, _ -> onEntry(f) })
        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = if (cols > 1) GridLayoutManager(requireContext(), cols) else LinearLayoutManager(requireContext())
            adapter = this@TrashFragment.adapter
            this@TrashFragment.adapter.glassColor = DeviceUi.glass(requireContext(), R.color.card_bg)
        }
        swipe.setOnRefreshListener { refresh() }
        val topInset = (requireActivity() as MainActivity).topInset
        if (!DeviceUi.isLarge(requireContext())) view.findViewById<RecyclerView>(R.id.list).apply {
            clipToPadding = false
            setPadding(paddingLeft, paddingTop + topInset, paddingRight, (100 * resources.displayMetrics.density).toInt())
        }
        swipe.setProgressViewOffset(false, topInset, topInset + (64 * resources.displayMetrics.density).toInt())
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun refresh() {
        if (!isAdded) return
        main.setNavMode(parentFragmentManager.backStackEntryCount > 0) { requireActivity().onBackPressedDispatcher.onBackPressed() }
        main.supportActionBar?.title = getString(R.string.tab_trash)
        swipe.isRefreshing = true
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            entries = withContext(Dispatchers.IO) { runCatching { Trash.list(ctx) }.getOrDefault(emptyList()) }
            swipe.isRefreshing = false
            adapter.items = entries.map {
                FileItem(it.file.name.substringAfter('_'), it.file.path, it.file.isDirectory,
                    if (it.file.isDirectory) 0 else it.file.length(), it.deletedAt)
            }
            empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            main.supportActionBar?.subtitle = getString(R.string.trash_count, entries.size)
        }
    }

    private fun onEntry(f: FileItem) {
        val e = entries.firstOrNull { it.file.path == f.path } ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(f.name)
            .setMessage(getString(R.string.trash_from, e.originalPath))
            .setPositiveButton(R.string.restore) { _, _ -> run({ Trash.restore(e) }) }
            .setNeutralButton(R.string.delete_forever) { _, _ -> run({ Trash.deleteForever(e) }) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun run(op: () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(op) }
            Toast.makeText(requireContext(),
                r.fold({ getString(R.string.done) }, { getString(R.string.failed, it.message ?: "") }), Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        menu.add(0, 1, 0, R.string.empty_trash)
        menu.add(0, 2, 0, R.string.theme)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.empty_trash)
                .setMessage(R.string.empty_trash_confirm)
                .setPositiveButton(R.string.delete_forever) { _, _ ->
                    val ctx = requireContext().applicationContext
                    run({ Trash.empty(ctx) })
                }
                .setNegativeButton(R.string.cancel, null).show()
            2 -> DeviceUi.chooseTheme(requireActivity(), main.prefs)
            else -> return false
        }
        return true
    }
}
