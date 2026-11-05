package com.mbnetwork.filemanager

import android.os.Bundle
import android.view.View
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    lateinit var prefs: Prefs
        private set
    private var currentTab = R.id.nav_files

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        DeviceUi.applyTheme(this, prefs) // White / Black / System, chosen in the menu
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        // Phone: bottom bar. Tablet / Android TV: side rail.
        val bottom = findViewById<BottomNavigationView>(R.id.bottomNav)
        val rail = findViewById<NavigationRailView>(R.id.navRail)
        val large = DeviceUi.isLarge(this)
        bottom.visibility = if (large) View.GONE else View.VISIBLE
        rail.visibility = if (large) View.VISIBLE else View.GONE
        val listener = NavigationBarView.OnItemSelectedListener {
            if (it.itemId != currentTab) {
                currentTab = it.itemId
                show(when (it.itemId) { R.id.nav_apps -> AppsFragment(); R.id.nav_trash -> TrashFragment(); else -> FilesFragment() })
            }
            true
        }
        bottom.setOnItemSelectedListener(listener)
        rail.setOnItemSelectedListener(listener)
        if (savedInstanceState == null) show(FilesFragment())

        // Ask for storage permission only if this Android version needs it and it is missing.
        PermissionHelper.requestIfNeeded(this)

        // Root features exist only on rooted devices.
        if (!RootShell.deviceRooted) prefs.rootMode = false

        // Re-acquire root if the user enabled root mode earlier.
        if (prefs.rootMode) {
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { RootShell.requestRoot() }
                if (!ok) {
                    prefs.rootMode = false
                    Toast.makeText(this@MainActivity, R.string.root_denied, Toast.LENGTH_LONG).show()
                }
                currentFragment()?.let { (it as? Refreshable)?.refresh() }
            }
        }
    }

    private fun show(f: Fragment) {
        supportFragmentManager.beginTransaction().replace(R.id.container, f).commit()
    }

    private fun currentFragment() = supportFragmentManager.findFragmentById(R.id.container)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionHelper.REQUEST_CODE) (currentFragment() as? Refreshable)?.refresh()
    }

    /** Toggle root mode; asks the root manager when switching on. */
    fun setRootMode(enable: Boolean, done: (Boolean) -> Unit) {
        if (!enable) {
            prefs.rootMode = false; done(false); return
        }
        if (!RootShell.deviceRooted) {
            prefs.rootMode = false
            Toast.makeText(this, R.string.not_rooted, Toast.LENGTH_LONG).show()
            done(false); return
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { RootShell.requestRoot() }
            prefs.rootMode = ok
            Toast.makeText(this@MainActivity, if (ok) R.string.root_granted else R.string.root_denied, Toast.LENGTH_LONG).show()
            done(ok)
        }
    }

    val rootActive get() = RootShell.deviceRooted && prefs.rootMode && RootShell.granted
}

interface Refreshable {
    fun refresh()
}
