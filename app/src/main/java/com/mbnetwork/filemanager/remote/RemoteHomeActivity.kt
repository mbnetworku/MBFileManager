package com.mbnetwork.filemanager.remote

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mbnetwork.filemanager.DeviceUi
import com.mbnetwork.filemanager.Prefs
import com.mbnetwork.filemanager.R
import java.util.UUID

/** "Network & cloud": saved servers (SMB, FTP, SFTP, WebDAV) and cloud accounts (Google Drive, OneDrive, Dropbox). */
class RemoteHomeActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private var wanted: OAuth.Provider? = null

    /** The system folder picker; the phone's cloud apps (Google Drive, OneDrive, Dropbox) appear in it. */
    private val pickCloud = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Throwable) { }
        val kind = FsSaf.providerOf(uri)
        val title = OAuth.all.firstOrNull { it.id == kind }?.title ?: getString(R.string.cloud_storage)
        val c = Conn(UUID.randomUUID().toString(), "saf", title, uri.toString(), 0, "", "", kind)
        ConnStore.save(this, c); render(); open(c)
    }
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()
    private fun col(r: Int) = ContextCompat.getColor(this, r)

    override fun onCreate(savedInstanceState: Bundle?) {
        DeviceUi.applyTheme(this, Prefs(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)
        DeviceUi.enterSlide(this)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.network_cloud)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), dp(30)) }
        findViewById<ViewGroup>(R.id.content).addView(ScrollView(this).apply { addView(list) })
    }

    override fun onResume() { super.onResume(); render() }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun finish() { super.finish(); DeviceUi.exitSlide(this) }

    // ------------------------------------------------------------------ screen

    private fun header(t: String) {
        list.addView(TextView(this).apply {
            text = t.uppercase(); textSize = 12f; setTextColor(col(R.color.text_secondary))
            setPadding(dp(8), dp(18), dp(8), dp(6))
        })
    }

    private fun row(icon: Int, tint: Int, title: String, sub: String?, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
        val v = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(col(R.color.card_bg)); cornerRadius = dp(14).toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            onLong?.let { setOnLongClickListener { _ -> it(); true } }
            addView(ImageView(context).apply {
                setImageResource(icon); ImageViewCompat.setImageTintList(this, android.content.res.ColorStateList.valueOf(tint))
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(14) }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply { text = title; textSize = 16f; setTextColor(col(R.color.label)); maxLines = 1 })
                if (sub != null) addView(TextView(context).apply { text = sub; textSize = 12f; setTextColor(col(R.color.text_secondary)); maxLines = 1 })
            })
        }
        list.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
    }

    private fun render() {
        list.removeAllViews()
        val conns = ConnStore.all(this)

        header(getString(R.string.local_network))
        conns.filter { !it.isCloud && !it.isSaf }.forEach { c ->
            row(R.drawable.ic_cloud, col(R.color.brand), c.name, "${ConnType.title(c.type)} · ${c.host}",
                { open(c) }, { manage(c) })
        }
        row(R.drawable.ic_add, col(R.color.brand), getString(R.string.add_connection), getString(R.string.add_connection_sub), { pickType() })
        row(R.drawable.ic_cloud, col(R.color.t_image), getString(R.string.scan_network), getString(R.string.scan_network_sub), { scan() })

        header(getString(R.string.cloud))
        for (p in OAuth.all) {
            val mine = conns.filter { it.type == p.id || (it.isSaf && it.path == p.id) }
            if (mine.isEmpty()) {
                val has = OAuth.clientId(this, p).isNotEmpty()
                row(R.drawable.ic_cloud, col(R.color.t_doc), p.title, getString(R.string.tap_sign_in),
                    { connectCloud(p) }, if (OAuth.builtIn(p)) null else ({ keysDialog(p) {} }))
            } else mine.forEach { c ->
                row(R.drawable.ic_cloud, col(R.color.t_doc), c.name, getString(R.string.connected), { open(c) }, { manage(c) })
            }
        }
        conns.filter { it.isSaf && it.path == "other" }.forEach { c -> row(R.drawable.ic_cloud, col(R.color.t_doc), c.name, getString(R.string.connected), { open(c) }, { manage(c) }) }
        if (!Secrets.available()) list.addView(TextView(this).apply {
            text = getString(R.string.old_android_note); textSize = 12f; setTextColor(col(R.color.text_secondary)); setPadding(dp(8), dp(10), dp(8), 0)
        })
    }

    private fun open(c: Conn) {
        startActivity(Intent(this, RemoteBrowserActivity::class.java).putExtra(RemoteBrowserActivity.EXTRA_ID, c.id))
    }

    private fun manage(c: Conn) {
        val items = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        items += getString(R.string.open); acts += { open(c) }
        if (c.isSaf) { /* nothing to edit: just open or remove */ }
        else if (!c.isCloud) { items += getString(R.string.edit); acts += { form(c.type, c, null) } }
        else if (OAuth.all.firstOrNull { it.id == c.type }?.let { !OAuth.builtIn(it) } == true) {
            items += getString(R.string.change_keys); acts += { OAuth.all.firstOrNull { it.id == c.type }?.let { keysDialog(it) {} } }
        }
        items += getString(if (c.isCloud || c.isSaf) R.string.sign_out else R.string.remove); acts += {
            if (c.isSaf) try {
                contentResolver.releasePersistableUriPermission(Uri.parse(c.host), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: Throwable) { }
            ConnStore.remove(this, c.id); render()
        }
        MaterialAlertDialogBuilder(this).setTitle(c.name).setItems(items.toTypedArray()) { _, i -> acts[i]() }.show()
    }

    // ------------------------------------------------------------------ cloud

    private fun connectCloud(p: OAuth.Provider) {
        if (OAuth.clientId(this, p).isNotEmpty()) { signIn(p); return }
        // No developer keys in this build: sign in through the official cloud app on the phone (normal sign-in, no keys).
        wanted = p
        MaterialAlertDialogBuilder(this).setTitle(p.title)
            .setMessage(getString(R.string.saf_message, p.title, p.title))
            .setPositiveButton(R.string.saf_continue) { _, _ ->
                try { pickCloud.launch(null) } catch (e: Throwable) { Toast.makeText(this, R.string.no_app_for_file, Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.advanced) { _, _ -> keysDialog(p) { signIn(p) } }.show()
    }

    private fun signIn(p: OAuth.Provider) {
        Toast.makeText(this, getString(R.string.opening_browser, p.title), Toast.LENGTH_LONG).show()
        OAuth.signIn(this, p) { conn, err ->
            if (conn != null) { ConnStore.save(this, conn); Toast.makeText(this, R.string.connected, Toast.LENGTH_SHORT).show(); render() }
            else MaterialAlertDialogBuilder(this).setTitle(p.title).setMessage(err ?: "").setPositiveButton(R.string.ok, null).show()
        }
    }

    private fun keysDialog(p: OAuth.Provider, then: () -> Unit) {
        val id = field(getString(R.string.client_id), OAuth.clientId(this, p))
        val sec = field(getString(R.string.client_secret), OAuth.clientSecret(this, p))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(context).apply {
                text = getString(R.string.keys_help, p.title, OAuth.REDIRECT); textSize = 13f; setTextColor(col(R.color.text_secondary))
            })
            addView(id)
            if (p.needsSecret) addView(sec)
        }
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.keys_title, p.title)).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.ok) { _, _ ->
                if (id.text.toString().isNotBlank()) { OAuth.saveKeys(this, p, id.text.toString(), sec.text.toString()); render(); then() }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun field(hint: String, value: String = "", password: Boolean = false, number: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(value); setSingleLine()
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            number -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
    }

    // ------------------------------------------------------------------ servers

    private fun pickType() {
        val types = listOf(ConnType.SMB, ConnType.FTP, ConnType.FTPS, ConnType.SFTP, ConnType.WEBDAV)
        MaterialAlertDialogBuilder(this).setTitle(R.string.add_connection)
            .setItems(types.map { ConnType.title(it) }.toTypedArray()) { _, i -> form(types[i], null, null) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun form(type: String, existing: Conn?, host0: String?) {
        val name = field(getString(R.string.conn_name), existing?.name ?: "")
        val host = field(getString(if (type == ConnType.WEBDAV) R.string.conn_url else R.string.conn_host), existing?.host ?: host0 ?: "")
        val port = field(getString(R.string.conn_port, ConnType.defaultPort(type)),
            existing?.port?.takeIf { it > 0 }?.toString() ?: "", number = true)
        val user = field(getString(R.string.conn_user), existing?.user ?: "")
        val pass = field(getString(if (existing != null) R.string.conn_pass_keep else R.string.password), "", password = true)
        val path = field(getString(when (type) { ConnType.SMB -> R.string.conn_share; else -> R.string.conn_folder }), existing?.path ?: "")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(name); addView(host)
            if (type != ConnType.WEBDAV) addView(port)
            addView(user); addView(pass); addView(path)
            if (type == ConnType.SMB) addView(TextView(context).apply {
                text = getString(R.string.smb_hint); textSize = 12f; setTextColor(col(R.color.text_secondary))
            })
        }
        MaterialAlertDialogBuilder(this).setTitle(ConnType.title(type)).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.save) { _, _ ->
                val h = host.text.toString().trim()
                if (h.isEmpty()) { Toast.makeText(this, R.string.conn_host, Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val pw = pass.text.toString()
                val c = Conn(existing?.id ?: UUID.randomUUID().toString(), type, name.text.toString().trim().ifEmpty { h },
                    h, port.text.toString().toIntOrNull() ?: 0, user.text.toString().trim(),
                    if (pw.isEmpty() && existing != null) existing.pass else Secrets.enc(pw), path.text.toString().trim())
                ConnStore.save(this, c); render()
                if (existing == null) open(c)
            }.setNegativeButton(R.string.cancel, null).show()
    }

    // ------------------------------------------------------------------ scan

    private var scanning = false

    private fun scan() {
        if (scanning) return                      // a scan is already running
        val ip = LanScanner.myIp()
        if (ip == null) { Toast.makeText(this, R.string.no_wifi, Toast.LENGTH_LONG).show(); return }
        scanning = true
        var cancelled = false
        val found = ArrayList<LanScanner.Host>()
        val msg = TextView(this).apply { text = getString(R.string.scanning, ip.substringBeforeLast('.') + ".x"); setPadding(dp(24), dp(16), dp(24), dp(8)) }
        val dlg = MaterialAlertDialogBuilder(this).setTitle(R.string.scan_network).setView(msg)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled = true }
            .setOnCancelListener { cancelled = true }.show()
        Thread {
            LanScanner.scan({ cancelled }) { h -> synchronized(found) { found += h } }
            runOnUiThread {
                scanning = false
                if (cancelled) return@runOnUiThread
                dlg.dismiss()
                if (found.isEmpty()) { Toast.makeText(this, R.string.scan_none, Toast.LENGTH_LONG).show(); return@runOnUiThread }
                val sorted = found.sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
                MaterialAlertDialogBuilder(this).setTitle(R.string.scan_found)
                    .setItems(sorted.map { "${it.ip}${if (it.name.isNotEmpty()) " (${it.name})" else ""} – ${it.services.joinToString { s -> ConnType.title(s) }}" }.toTypedArray()) { _, i ->
                        val h = sorted[i]
                        if (h.services.size == 1) form(h.services[0], null, h.ip)
                        else MaterialAlertDialogBuilder(this).setTitle(h.ip)
                            .setItems(h.services.map { ConnType.title(it) }.toTypedArray()) { _, k -> form(h.services[k], null, h.ip) }.show()
                    }.setNegativeButton(R.string.cancel, null).show()
            }
        }.start()
    }
}
