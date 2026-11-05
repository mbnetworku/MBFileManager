package com.mbnetwork.filemanager

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppsFragment : Fragment(R.layout.fragment_apps), Refreshable, MenuProvider {

    private val main get() = requireActivity() as MainActivity
    private val prefs get() = main.prefs

    private lateinit var swipe: SwipeRefreshLayout
    private val adapter = AppAdapter(::onAppClick)
    private var all: List<AppItem> = emptyList()
    private var query = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        swipe = view.findViewById(R.id.swipe)
        view.findViewById<RecyclerView>(R.id.list).apply {
            val cols = DeviceUi.appColumns(requireContext())
            layoutManager = if (cols > 1) androidx.recyclerview.widget.GridLayoutManager(requireContext(), cols)
                else LinearLayoutManager(requireContext())
            adapter = this@AppsFragment.adapter
        }
        swipe.setOnRefreshListener { refresh() }
        if (!DeviceUi.isLarge(requireContext())) view.findViewById<RecyclerView>(R.id.list).apply {
            clipToPadding = false
            setPadding(paddingLeft, paddingTop, paddingRight, (100 * resources.displayMetrics.density).toInt())
        }
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()
        refresh() // reload after returning from the system uninstall dialog
    }

    override fun refresh() {
        if (!isAdded) return
        main.supportActionBar?.title = getString(R.string.tab_apps)
        swipe.isRefreshing = true
        val pm = requireContext().packageManager
        viewLifecycleOwner.lifecycleScope.launch {
            all = withContext(Dispatchers.IO) { loadApps(pm) }
            swipe.isRefreshing = false
            applyFilter()
        }
    }

    @Suppress("DEPRECATION")
    private fun loadApps(pm: PackageManager): List<AppItem> {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            PackageManager.MATCH_UNINSTALLED_PACKAGES else PackageManager.GET_UNINSTALLED_PACKAGES
        return pm.getInstalledApplications(flags).map { ai ->
            val version = try { pm.getPackageInfo(ai.packageName, flags).versionName ?: "" } catch (e: Exception) { "" }
            AppItem(
                pkg = ai.packageName,
                label = ai.loadLabel(pm).toString(),
                icon = try { ai.loadIcon(pm) } catch (e: Exception) { null },
                isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                enabled = ai.enabled,
                installed = (ai.flags and ApplicationInfo.FLAG_INSTALLED) != 0,
                sourceDir = ai.sourceDir ?: "",
                version = version
            )
        }.sortedBy { it.label.lowercase() }
    }

    private fun applyFilter() {
        val q = query.lowercase()
        // "Removed" system apps are only listed on rooted devices, where they can be restored.
        val list = all.filter { (prefs.showSystemApps || !it.isSystem) && (it.installed || (prefs.showSystemApps && RootShell.deviceRooted)) }
            .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
        adapter.items = list
        main.supportActionBar?.subtitle = getString(R.string.apps_count, list.size) +
            if (main.rootActive) "  •  ROOT" else ""
    }

    private fun onAppClick(a: AppItem) {
        val ctx = requireContext()
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        val launch = ctx.packageManager.getLaunchIntentForPackage(a.pkg)
        if (a.installed) {
            if (launch != null) actions += getString(R.string.app_open) to { startActivity(launch) }
            actions += getString(R.string.app_info) to {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
            }
            if (!a.isSystem) {
                actions += getString(R.string.app_uninstall) to { uninstallUserApp(a) }
            } else if (RootShell.deviceRooted) {
                actions += getString(R.string.app_uninstall_root) to { uninstallSystemApp(a) }
            }
            if (main.rootActive && a.pkg != ctx.packageName) {
                if (a.enabled) actions += getString(R.string.app_disable_root) to { rootCmd("pm disable-user --user 0 ${a.pkg} || pm disable ${a.pkg}") }
                else actions += getString(R.string.app_enable_root) to { rootCmd("pm enable ${a.pkg}") }
            }
            if (a.sourceDir.isNotEmpty()) actions += getString(R.string.app_extract_apk) to { extractApk(a) }
        } else if (main.rootActive) {
            actions += getString(R.string.app_restore_root) to {
                rootCmd("cmd package install-existing ${a.pkg} || pm install-existing ${a.pkg}")
            }
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(a.label)
            .setIcon(a.icon)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Normal apps: use the standard Android uninstall dialog (no root needed). */
    @Suppress("DEPRECATION")
    private fun uninstallUserApp(a: AppItem) {
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:${a.pkg}"))
            .putExtra(Intent.EXTRA_RETURN_RESULT, false)
        try { startActivity(intent) } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))
        }
    }

    /** System apps can only be removed with root: `pm uninstall --user 0` (restorable later). */
    private fun uninstallSystemApp(a: AppItem) {
        if (!main.rootActive) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(a.label)
                .setMessage(R.string.system_app_needs_root)
                .setPositiveButton(R.string.root_mode) { _, _ -> main.setRootMode(true) { ok -> if (ok) uninstallSystemApp(a) } }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.app_uninstall_root)
            .setMessage(getString(R.string.system_uninstall_confirm, a.label))
            .setPositiveButton(R.string.app_uninstall) { _, _ -> rootCmd("pm uninstall -k --user 0 ${a.pkg}") }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun rootCmd(cmd: String) {
        swipe.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { RootShell.run(cmd) } }
            val res = r.getOrNull()
            val ok = res != null && res.ok && !res.out.contains("Failure")
            Toast.makeText(requireContext(),
                if (ok) getString(R.string.done) else getString(R.string.failed, res?.let { it.err + it.out }?.trim() ?: r.exceptionOrNull()?.message ?: ""),
                Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    private fun extractApk(a: AppItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
                    val out = File(dir, "${a.label.replace(Regex("[^A-Za-z0-9._ -]"), "_")}_${a.version}.apk")
                    File(a.sourceDir).copyTo(out, overwrite = true)
                    out.absolutePath
                }
            }
            Toast.makeText(requireContext(),
                r.fold({ "Saved: $it" }, { getString(R.string.failed, it.message ?: "") }), Toast.LENGTH_LONG).show()
        }
    }

    /** Scans storage for APK files and installs the one the user picks. */
    private fun pickApkToInstall() {
        if (!PermissionHelper.hasStoragePermission(requireContext())) {
            PermissionHelper.requestIfNeeded(requireActivity()); return
        }
        Toast.makeText(requireContext(), R.string.searching_apks, Toast.LENGTH_SHORT).show()
        swipe.isRefreshing = true
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val apks = withContext(Dispatchers.IO) {
                val roots = mutableListOf(Environment.getExternalStorageDirectory())
                androidx.core.content.ContextCompat.getExternalFilesDirs(ctx, null).drop(1).filterNotNull()
                    .forEach { roots += File(it.absolutePath.substringBefore("/Android/data")) }
                ApkInstaller.findApks(roots)
            }
            swipe.isRefreshing = false
            if (apks.isEmpty()) {
                Toast.makeText(ctx, R.string.no_apks, Toast.LENGTH_LONG).show(); return@launch
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.install_apk)
                .setItems(apks.map { "${it.name}\n${it.parent}" }.toTypedArray()) { _, i ->
                    ApkInstaller.install(requireActivity(), apks[i])
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ---- menu ----
    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.apps_menu, menu)
        (menu.findItem(R.id.action_search).actionView as SearchView).setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = true
            override fun onQueryTextChange(q: String?): Boolean { query = q.orEmpty(); applyFilter(); return true }
        })
    }

    override fun onPrepareMenu(menu: Menu) {
        menu.findItem(R.id.action_system).isChecked = prefs.showSystemApps
        menu.findItem(R.id.action_root).isVisible = RootShell.deviceRooted  // hidden on non-rooted devices
        menu.findItem(R.id.action_root).isChecked = main.rootActive
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_system -> { prefs.showSystemApps = !prefs.showSystemApps; item.isChecked = prefs.showSystemApps; applyFilter() }
            R.id.action_root -> main.setRootMode(!main.rootActive) { requireActivity().invalidateOptionsMenu(); applyFilter() }
            R.id.action_refresh -> refresh()
            R.id.action_install -> pickApkToInstall()
            R.id.action_theme -> DeviceUi.chooseTheme(requireActivity(), prefs)
            else -> return false
        }
        return true
    }
}
