package com.mbnetwork.filemanager

import android.os.Bundle
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
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
    private lateinit var drawer: DrawerLayout
    private lateinit var sidebar: Sidebar
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var railNav: NavigationRailView
    /** Location chosen in the sidebar while another tab was open. */
    var pendingLocation: String? = null
    private val drawerBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeDrawer()
    }
    private var currentTab = R.id.nav_files

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        DeviceUi.applyTheme(this, prefs) // White / Black / System, chosen in the menu
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Liquid Glass: soft colored backdrop behind translucent surfaces
        if (DeviceUi.liquid(this)) {
            window.setBackgroundDrawable(DeviceUi.liquidBackground(this))
            toolbar.background?.mutate()?.alpha = 200
        }

        // Sidebar (hidden until opened with the ☰ button or a swipe from the left edge)
        drawer = findViewById(R.id.drawer)
        sidebar = Sidebar(this, findViewById(R.id.drawerContent))
        // Sidebar must always be readable: (almost) solid panel; content behind gets dimmed / blurred.
        val panelColor = androidx.core.content.ContextCompat.getColor(this, R.color.sheet_bg)
        findViewById<View>(R.id.drawerPanel).setBackgroundColor(
            if (DeviceUi.liquid(this) && android.os.Build.VERSION.SDK_INT >= 31) (panelColor and 0x00FFFFFF) or (0xE6 shl 24) else panelColor)
        drawer.setScrimColor(0x99000000.toInt())
        val mainContent = drawer.getChildAt(0)
        val toggle = androidx.appcompat.app.ActionBarDrawerToggle(this, drawer, toolbar, R.string.drawer_open, R.string.drawer_close)
        drawer.addDrawerListener(toggle)
        toggle.syncState()
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(v: View, offset: Float) {
                if (DeviceUi.liquid(this@MainActivity)) DeviceUi.blur(mainContent, 24f * offset)
            }
            override fun onDrawerStateChanged(state: Int) {
                if (state == DrawerLayout.STATE_SETTLING && !drawer.isDrawerOpen(GravityCompat.START)) sidebar.build()
            }
            override fun onDrawerOpened(v: View) {
                drawerBack.isEnabled = true
                // newest callback wins: re-register so Back closes the sidebar first
                drawerBack.remove(); onBackPressedDispatcher.addCallback(this@MainActivity, drawerBack)
            }
            override fun onDrawerClosed(v: View) { drawerBack.isEnabled = false }
        })
        sidebar.build()

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
        // landscape: keep the pill compact and centered
        if (resources.configuration.screenWidthDp > 560) (bottom.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            width = (440 * resources.displayMetrics.density).toInt()
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = (8 * resources.displayMetrics.density).toInt()
        }
        // floating rounded glass pill, brand blue selection (no purple)
        bottom.background = DeviceUi.pill(this)
        bottom.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        bottom.clipToOutline = true
        val sel = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(androidx.core.content.ContextCompat.getColor(this, R.color.grad_start),
                androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary)))
        bottom.itemIconTintList = sel; bottom.itemTextColor = sel
        rail.itemIconTintList = sel; rail.itemTextColor = sel
        rail.setBackgroundColor(DeviceUi.glass(this, R.color.card_bg))
        bottomNav = bottom; railNav = rail
        bottom.setOnItemSelectedListener(listener)
        rail.setOnItemSelectedListener(listener)
        if (savedInstanceState == null) show(FilesFragment())

        // Ask for storage permission only if this Android version needs it and it is missing.
        PermissionHelper.requestIfNeeded(this)

        // Look for a newer version (at most once a day)
        Updater.check(this, lifecycleScope, manual = false)

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

    fun openDrawer() = drawer.openDrawer(GravityCompat.START)
    fun closeDrawer() = drawer.closeDrawer(GravityCompat.START)
    fun checkUpdates() = Updater.check(this, lifecycleScope, manual = true)

    fun selectTab(id: Int) {
        closeDrawer()
        if (DeviceUi.isLarge(this)) railNav.selectedItemId = id else bottomNav.selectedItemId = id
    }

    /** Sidebar: open a folder in the Files tab. */
    fun openLocation(path: String) {
        closeDrawer()
        val f = currentFragment()
        if (f is FilesFragment) f.openLocation(path)
        else { pendingLocation = path; selectTab(R.id.nav_files) }
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
