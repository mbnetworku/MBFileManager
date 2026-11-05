package com.mbnetwork.filemanager

import android.os.Bundle
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import android.view.View
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
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
    private lateinit var toggle: androidx.appcompat.app.ActionBarDrawerToggle
    private lateinit var sidebar: Sidebar
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var railNav: NavigationRailView
    /** Location chosen in the sidebar while another tab was open. */
    var pendingLocation: String? = null
    private val drawerBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeDrawer()
    }
    private var currentTab = R.id.nav_home
    /** Space (px) the screen must leave at the top because the glass bar floats over it (0 on tablet / TV). */
    var topInset = 0
        private set
    /** Space (px) the screen leaves at the bottom for the floating tab bar (0 on tablet / TV / car). */
    var bottomInset = 0
        private set

    // ---- mini player (shown above the tab bar while music plays)
    private var miniDismissedFor: String? = null      // song whose mini bar was swiped away
    private var miniSuppressed = false                // hidden during Select mode
    private var miniShownPath: String? = null
    private var miniExtra = 0
    private val miniObserver: () -> Unit = { runOnUiThread { updateMini() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        MusicPlayer.appContext = applicationContext
        DeviceUi.applyTheme(this, prefs) // White / Black / System, chosen in the menu
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.overflowIcon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_more_circle)

        // iOS look: transparent status bar, dark/light icons follow the theme
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !night

        // Liquid Glass bars: the screen scrolls UNDER the top bar and the floating tab bar and is blurred there
        val container = findViewById<View>(R.id.container)
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)
        val barPx = android.util.TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
        val bigScreen = DeviceUi.isLarge(this)
        topInset = if (bigScreen) 0 else barPx                 // phones: content starts under the glass bar
        bottomInset = if (bigScreen) 0 else resources.getDimensionPixelSize(R.dimen.nav_bottom_inset)
        if (bigScreen) findViewById<View>(R.id.row).setPadding(0, barPx, 0, 0)  // tablet / TV: content starts below it
        findViewById<GlassView>(R.id.topGlass).setup(container, shape = GlassView.SHAPE_NONE, rim = GlassView.RIM_BOTTOM, tintAlpha = 0.62f)
        findViewById<GlassView>(R.id.bottomGlass).setup(container, capsule = true, tintAlpha = 0.58f)
        setupMini(container, bigScreen)
        findViewById<GlassView>(R.id.drawerPanel).setup(findViewById(R.id.root), radiusDp = 28f, tintAlpha = 0.80f)

        // Sidebar (hidden until opened with the ☰ button or a swipe from the left edge)
        drawer = findViewById(R.id.drawer)
        sidebar = Sidebar(this, findViewById(R.id.drawerContent))
        drawer.setScrimColor(0x66000000)
        val mainContent = drawer.getChildAt(0)
        toggle = androidx.appcompat.app.ActionBarDrawerToggle(this, drawer, toolbar, R.string.drawer_open, R.string.drawer_close)
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
        val bottomGlass = findViewById<GlassView>(R.id.bottomGlass)
        val rail = findViewById<NavigationRailView>(R.id.navRail)
        val large = DeviceUi.isLarge(this)
        bottomGlass.visibility = if (large) View.GONE else View.VISIBLE
        rail.visibility = if (large) View.VISIBLE else View.GONE
        val listener = NavigationBarView.OnItemSelectedListener {
            if (it.itemId != currentTab) {
                currentTab = it.itemId
                popToRoot()
                when (it.itemId) {
                    R.id.nav_recents -> {
                        pendingCategory = getString(R.string.tab_recents) to { c: android.content.Context -> FileIndex.recent(c, 100) }
                        pendingPinned = true
                        show(FilesFragment())
                    }
                    R.id.nav_apps -> show(AppsFragment())
                    else -> show(HomeFragment())
                }
            }
            true
        }
        val reselected = NavigationBarView.OnItemReselectedListener { popToRoot() }
        // landscape: keep the capsule compact and centered
        if (resources.configuration.screenWidthDp > 560) (bottomGlass.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            width = (330 * resources.displayMetrics.density).toInt()
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = (6 * resources.displayMetrics.density).toInt()
        }
        // the glass capsule is the background; iOS blue for the selected tab
        bottom.background = null
        val sel = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(androidx.core.content.ContextCompat.getColor(this, R.color.brand),
                androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary)))
        bottom.itemIconTintList = sel; bottom.itemTextColor = sel
        rail.itemIconTintList = sel; rail.itemTextColor = sel
        rail.setBackgroundColor(DeviceUi.glass(this, R.color.card_bg))
        bottomNav = bottom; railNav = rail
        bottom.selectedItemId = R.id.nav_home          // start on Browse
        rail.selectedItemId = R.id.nav_home
        bottom.setOnItemSelectedListener(listener); bottom.setOnItemReselectedListener(reselected)
        rail.setOnItemSelectedListener(listener); rail.setOnItemReselectedListener(reselected)
        if (savedInstanceState == null) show(HomeFragment()) // start on the Home screen

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

    /** Live detection: USB drive / SD card plugged in or removed. */
    private var knownVolumes = setOf<String>()
    private val mediaReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
            // mounting takes a moment after the USB is attached
            listOf(400L, 2500L).forEach { drawer.postDelayed({ onVolumesChanged() }, it) }
        }
    }

    private fun onVolumesChanged() {
        if (isFinishing) return
        val now = StorageVolumes.list(this)
        val added = now.firstOrNull { it.path !in knownVolumes && knownVolumes.isNotEmpty() }
        val removed = knownVolumes.size > now.size
        knownVolumes = now.map { it.path }.toSet()
        sidebar.build()
        (currentFragment() as? Refreshable)?.let { if (it is HomeFragment) it.refresh() }
        if (added != null) {
            com.google.android.material.snackbar.Snackbar.make(findViewById(R.id.container),
                getString(R.string.volume_connected, added.label), com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction(R.string.open) { openLocation(added.path) }.show()
        } else if (removed) {
            Toast.makeText(this, R.string.volume_removed, Toast.LENGTH_SHORT).show()
            val f = currentFragment()
            if (f is FilesFragment) f.openLocation(android.os.Environment.getExternalStorageDirectory().absolutePath)
        }
    }

    override fun onStart() {
        super.onStart()
        MusicPlayer.addObserver(miniObserver)
        updateMini()
        knownVolumes = StorageVolumes.list(this).map { it.path }.toSet()
        val media = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_MEDIA_MOUNTED)
            addAction(android.content.Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(android.content.Intent.ACTION_MEDIA_REMOVED)
            addAction(android.content.Intent.ACTION_MEDIA_EJECT)
            addAction(android.content.Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        val usb = android.content.IntentFilter().apply {
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        androidx.core.content.ContextCompat.registerReceiver(this, mediaReceiver, media, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        androidx.core.content.ContextCompat.registerReceiver(this, usbReceiver, usb, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        onVolumesChanged()
    }

    private val usbReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
            listOf(1500L, 4000L, 8000L).forEach { drawer.postDelayed({ onVolumesChanged() }, it) }
        }
    }

    override fun onStop() {
        super.onStop()
        MusicPlayer.removeObserver(miniObserver)
        try { unregisterReceiver(mediaReceiver); unregisterReceiver(usbReceiver) } catch (e: Exception) { }
    }

    fun openDrawer() = drawer.openDrawer(GravityCompat.START)
    fun closeDrawer() = drawer.closeDrawer(GravityCompat.START)
    fun checkUpdates() = Updater.check(this, lifecycleScope, manual = true)

    fun selectTab(id: Int) {
        closeDrawer()
        if (id == R.id.nav_trash) { openTrash(); return }
        val nav = if (DeviceUi.isLarge(this)) railNav else bottomNav
        if (nav.selectedItemId == id) popToRoot() else nav.selectedItemId = id
    }

    private fun popToRoot() {
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    /** Back arrow (folder screens) or the sidebar button (Browse / Apps), like the iOS Files header. */
    fun setNavMode(back: Boolean, onBack: (() -> Unit)? = null) {
        if (back) {
            toggle.isDrawerIndicatorEnabled = false
            toggle.setHomeAsUpIndicator(androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_back))
            toggle.setToolbarNavigationClickListener { onBack?.invoke() }
        } else {
            toggle.isDrawerIndicatorEnabled = true
        }
    }

    // ------------------------------------------------------------------ mini player

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupMini(container: View, bigScreen: Boolean) {
        val mini = findViewById<GlassView>(R.id.miniGlass)
        val d = resources.displayMetrics.density
        mini.setup(container, radiusDp = 22f, tintAlpha = 0.66f)
        if (bigScreen) (mini.layoutParams as android.widget.FrameLayout.LayoutParams).bottomMargin = (12 * d).toInt()
        findViewById<View>(R.id.miniPlay).setOnClickListener { MusicPlayer.toggle() }
        findViewById<View>(R.id.miniNext).setOnClickListener { MusicPlayer.next() }

        // tap = open the player, swipe down = hide the bar (the music keeps playing)
        mini.setOnTouchListener(object : View.OnTouchListener {
            var downY = 0f
            var dragged = false
            override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { downY = e.rawY; dragged = false; return true }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dy = e.rawY - downY
                        if (dy > 8 * d) dragged = true
                        if (dragged) v.translationY = dy.coerceAtLeast(0f)
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val dy = e.rawY - downY
                        when {
                            !dragged && kotlin.math.abs(dy) < 8 * d ->
                                startActivity(android.content.Intent(this@MainActivity, PlayerActivity::class.java))
                            dy > 40 * d -> dismissMini(v)
                            else -> v.animate().translationY(0f).setDuration(160).start()
                        }
                        return true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> { v.animate().translationY(0f).setDuration(160).start(); return true }
                }
                return false
            }
        })

        // lists get extra room at the bottom while the bar is visible
        supportFragmentManager.registerFragmentLifecycleCallbacks(object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: androidx.fragment.app.FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                v.post { applyMiniInset(miniExtra) }
            }
        }, false)
    }

    private fun dismissMini(v: View) {
        miniDismissedFor = MusicPlayer.current?.absolutePath
        v.animate().translationY(v.height + 80 * resources.displayMetrics.density).alpha(0f).setDuration(180).withEndAction {
            v.visibility = View.GONE; v.translationY = 0f; v.alpha = 1f
            miniShownPath = null
            applyMiniInset(0)
        }.start()
    }

    private fun updateMini() {
        val mini = findViewById<GlassView>(R.id.miniGlass) ?: return
        val song = MusicPlayer.current
        val show = MusicPlayer.active && song != null && !miniSuppressed && song.absolutePath != miniDismissedFor
        if (!show) {
            if (mini.visibility == View.VISIBLE) { mini.visibility = View.GONE; miniShownPath = null }
            applyMiniInset(0)
            return
        }
        song!!
        findViewById<TextView>(R.id.miniTitle).text = song.nameWithoutExtension
        findViewById<TextView>(R.id.miniSub).text = song.parentFile?.name ?: ""
        findViewById<ImageButton>(R.id.miniPlay).setImageResource(if (MusicPlayer.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        if (miniShownPath != song.absolutePath) {
            miniShownPath = song.absolutePath
            val cover = findViewById<ImageView>(R.id.miniCover)
            cover.setImageDrawable(FileIcons.drawable(FileItem(song.name, song.absolutePath, false, 0, 0)))
            cover.background = null
            cover.scaleType = ImageView.ScaleType.FIT_CENTER
            cover.clipToOutline = false
            ThumbLoader.load(this, song.absolutePath, cover) {
                cover.scaleType = ImageView.ScaleType.CENTER_CROP
                cover.setBackgroundResource(R.drawable.bg_thumb_win)
                cover.clipToOutline = true
            }
        }
        if (mini.visibility != View.VISIBLE) {
            mini.alpha = 0f
            mini.translationY = 40 * resources.displayMetrics.density
            mini.visibility = View.VISIBLE
            mini.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
        applyMiniInset((68 * resources.displayMetrics.density).toInt())
    }

    /** Gives the current screen's list extra bottom room (and lifts the FAB) while the mini bar is visible. */
    private fun applyMiniInset(extra: Int) {
        miniExtra = extra
        val root = findViewById<android.view.ViewGroup>(R.id.container)?.getChildAt(0) ?: return
        findScroller(root)?.let { s ->
            val base = (s.getTag(R.id.tag_base_pad) as? Int) ?: s.paddingBottom.also { s.setTag(R.id.tag_base_pad, it) }
            s.setPadding(s.paddingLeft, s.paddingTop, s.paddingRight, base + extra)
        }
        root.findViewById<View>(R.id.fab)?.translationY = -extra.toFloat()
    }

    private fun findScroller(v: View): android.view.ViewGroup? {
        if (v is androidx.recyclerview.widget.RecyclerView || v is android.widget.ScrollView) return v as android.view.ViewGroup
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findScroller(v.getChildAt(i))?.let { return it }
        return null
    }

    /** During Select mode the tab bar makes room for the batch toolbar (iOS behaviour). */
    fun setTabBarVisible(visible: Boolean) {
        miniSuppressed = !visible
        updateMini()
        if (DeviceUi.isLarge(this)) return
        val g = findViewById<View>(R.id.bottomGlass)
        g.animate().cancel()
        if (visible) {
            g.visibility = View.VISIBLE
            g.animate().alpha(1f).translationY(0f).setDuration(200).start()
        } else {
            g.animate().alpha(0f).translationY(g.height.toFloat()).setDuration(200)
                .withEndAction { g.visibility = View.INVISIBLE }.start()
        }
    }

    /** Category / search result shown in a folder screen. */
    var pendingCategory: Pair<String, (android.content.Context) -> List<FileItem>>? = null
    /** true = the list is the root of a tab (Recents): Back does not leave it. */
    var pendingPinned = false

    fun openCategory(title: String, loader: (android.content.Context) -> List<FileItem>) {
        closeDrawer()
        val f = currentFragment()
        if (f is FilesFragment) f.openCategory(title, loader)
        else { pendingCategory = title to loader; show(FilesFragment(), push = true) }
    }

    /** Open a folder (sidebar / Browse list). */
    fun openLocation(path: String) {
        closeDrawer()
        val f = currentFragment()
        if (f is FilesFragment) f.openLocation(path)
        else { pendingLocation = path; show(FilesFragment(), push = true) }
    }

    fun openTrash() {
        closeDrawer()
        show(TrashFragment(), push = true)
    }

    private fun show(f: Fragment, push: Boolean = false) {
        val t = supportFragmentManager.beginTransaction()
        t.replace(R.id.container, f)
        if (push) t.addToBackStack(null)
        t.commit()
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
