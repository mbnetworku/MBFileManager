package com.mbnetwork.filemanager

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.driver.BlockDeviceDriverFactory
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileInputStream
import me.jahnen.libaums.core.usb.UsbCommunication
import java.io.File

/**
 * USB drive tools (works without the phone mounting the drive):
 * detect file system, browse FAT32 + copy to phone, rename drive, format as FAT32,
 * and (root) mount NTFS / exFAT drives.
 */
class UsbToolsActivity : AppCompatActivity() {

    private lateinit var box: LinearLayout
    private val d by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * d).toInt()
    private val usb by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private var pending: (() -> Unit)? = null

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (ok) pending?.invoke() else Toast.makeText(c, R.string.usb_perm_denied, Toast.LENGTH_LONG).show()
            pending = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DeviceUi.applyTheme(this, Prefs(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_office)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.usb_tools)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), px(12), px(12), px(24)) }
        findViewById<FrameLayout>(R.id.content).addView(ScrollView(this).apply { addView(box) })
        ContextCompat.registerReceiver(this, permReceiver, IntentFilter(ACTION_PERM), ContextCompat.RECEIVER_NOT_EXPORTED)
        render()
    }

    override fun onDestroy() { super.onDestroy(); try { unregisterReceiver(permReceiver) } catch (e: Exception) { } }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun card(v: View) = MaterialCardView(this).apply {
        radius = 18 * d; DeviceUi.styleCard(this)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, px(6), 0, px(6)) }
        addView(v)
    }

    private fun text(t: String, size: Float = 14f, bold: Boolean = false, alpha: Float = 1f) = TextView(this).apply {
        text = t; textSize = size; this.alpha = alpha; if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(t: Int, danger: Boolean = false, act: () -> Unit) = MaterialButton(this).apply {
        setText(t); isAllCaps = false
        if (danger) setBackgroundColor(ContextCompat.getColor(context, R.color.danger))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4) }
        setOnClickListener { act() }
    }

    private fun render() {
        box.removeAllViews()
        box.addView(text(getString(R.string.usb_tools_intro), 13f, alpha = 0.75f).apply { setPadding(px(6), 0, px(6), px(8)) })
        val devices = UsbMassStorageDevice.getMassStorageDevices(this)
        if (devices.isEmpty()) {
            box.addView(card(text(getString(R.string.usb_none), 15f).apply { setPadding(px(18), px(24), px(18), px(24)); gravity = Gravity.CENTER }))
        }
        devices.forEach { dev -> box.addView(deviceCard(dev)) }
        box.addView(button(R.string.refresh) { render() })
        box.addView(button(R.string.usb_system_settings) {
            try { startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        })
    }

    private fun deviceCard(dev: UsbMassStorageDevice): View {
        val ud: UsbDevice = dev.usbDevice
        val name = listOfNotNull(if (Build.VERSION.SDK_INT >= 21) ud.manufacturerName else null, if (Build.VERSION.SDK_INT >= 21) ud.productName else null)
            .joinToString(" ").ifBlank { getString(R.string.usb_drive) }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(14), px(16), px(14)) }
        val info = text(getString(R.string.usb_tap_to_read), 13f, alpha = 0.7f)
        inner.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(android.widget.ImageView(context).apply {
                setImageResource(R.drawable.ic_usb)
                background = DeviceUi.tile(context, ContextCompat.getColor(context, R.color.t_ppt), 12f)
                setPadding(px(9), px(9), px(9), px(9))
                layoutParams = LinearLayout.LayoutParams(px(44), px(44))
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(px(12), 0, 0, 0)
                addView(text(name, 16f, true)); addView(info)
            })
        })
        inner.addView(button(R.string.usb_read_info) { withDevice(dev) { b, _ -> readInfo(dev, b, info) } })
        inner.addView(button(R.string.usb_browse) { withDevice(dev) { _, _ -> browse(dev) } })
        inner.addView(button(R.string.usb_rename) { withDevice(dev) { b, _ -> rename(dev, b) } })
        inner.addView(button(R.string.usb_format, danger = true) { withDevice(dev) { b, _ -> askFormat(dev, b, name) } })
        if (RootShell.deviceRooted) inner.addView(button(R.string.usb_mount_root) { mountWithRoot() })
        return card(inner)
    }

    /** Gets permission, opens the device (even if no readable file system) and returns its raw block device. */
    private fun withDevice(dev: UsbMassStorageDevice, action: (BlockDeviceDriver, Boolean) -> Unit) {
        val run = {
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        try { dev.init() } catch (e: Exception) { /* unknown partition table is fine for formatting */ }
                        val f = UsbMassStorageDevice::class.java.getDeclaredField("usbCommunication").apply { isAccessible = true }
                        val comm = f.get(dev) as UsbCommunication
                        val block = BlockDeviceDriverFactory.createBlockDevice(comm, 0)
                        block.init()
                        block
                    }
                }
                r.onSuccess { action(it, true) }
                    .onFailure { Toast.makeText(this@UsbToolsActivity, getString(R.string.failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
            }
            Unit
        }
        if (usb.hasPermission(dev.usbDevice)) run() else {
            pending = run
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usb.requestPermission(dev.usbDevice, PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERM).setPackage(packageName), flags))
        }
    }

    private fun readInfo(dev: UsbMassStorageDevice, b: BlockDeviceDriver, info: TextView) {
        lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) {
                runCatching {
                    val fs = FatFormatter.detect(b)
                    val size = Formatter.formatFileSize(this@UsbToolsActivity, (b.blocks + 1) * b.blockSize)
                    val label = runCatching { dev.partitions.firstOrNull()?.fileSystem?.volumeLabel }.getOrNull().orEmpty()
                    val free = runCatching { dev.partitions.firstOrNull()?.fileSystem?.freeSpace }.getOrNull()
                    buildString {
                        append("$fs • $size")
                        if (label.isNotBlank()) append(" • \"$label\"")
                        if (free != null) append("\n").append(getString(R.string.free_space, Formatter.formatFileSize(this@UsbToolsActivity, free)))
                        if (fs == "NTFS" || fs == "exFAT") append("\n").append(getString(R.string.usb_ntfs_hint))
                    }
                }.getOrElse { it.message ?: "?" }
            }
            info.text = s; info.alpha = 0.9f
        }
    }

    // ---------- browse FAT32 + copy to phone ----------
    private fun browse(dev: UsbMassStorageDevice) {
        val fs = runCatching { dev.partitions.firstOrNull()?.fileSystem }.getOrNull()
        if (fs == null) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.usb_browse).setMessage(R.string.usb_not_fat32)
                .setPositiveButton(R.string.ok, null).show(); return
        }
        showDir(fs.rootDirectory, fs)
    }

    private fun showDir(dir: UsbFile, fs: me.jahnen.libaums.core.fs.FileSystem) {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { runCatching { dir.listFiles().sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) }.getOrDefault(emptyList()) }
            val names = files.map { (if (it.isDirectory) "📁 " else "📄 ") + it.name + if (!it.isDirectory) "  (${Formatter.formatShortFileSize(this@UsbToolsActivity, it.length)})" else "" }
            val b = MaterialAlertDialogBuilder(this@UsbToolsActivity)
                .setTitle(if (dir.isRoot) (fs.volumeLabel.ifBlank { getString(R.string.usb_drive) }) else dir.name)
                .setItems(names.toTypedArray()) { _, i -> val f = files[i]; if (f.isDirectory) showDir(f, fs) else copyToPhone(f, fs) }
                .setNegativeButton(R.string.close, null)
            if (!dir.isRoot) b.setNeutralButton(R.string.copy_folder) { _, _ -> copyToPhone(dir, fs) }
            if (files.isEmpty()) b.setMessage(R.string.empty_folder)
            b.show()
        }
    }

    private fun copyToPhone(src: UsbFile, fs: me.jahnen.libaums.core.fs.FileSystem) {
        val dest = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "USB")
        val bar = LinearProgressIndicator(this).apply { isIndeterminate = true }
        val dlg = MaterialAlertDialogBuilder(this).setTitle(getString(R.string.copying, src.name))
            .setView(FrameLayout(this).apply { setPadding(px(24), px(8), px(24), 0); addView(bar) }).setCancelable(false).show()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { copyRec(src, dest, fs); File(dest, src.name).path } }
            dlg.dismiss()
            Toast.makeText(this@UsbToolsActivity, r.fold({ getString(R.string.copied_to, it) }, { getString(R.string.failed, it.message ?: "") }), Toast.LENGTH_LONG).show()
        }
    }

    private fun copyRec(src: UsbFile, destDir: File, fs: me.jahnen.libaums.core.fs.FileSystem) {
        destDir.mkdirs()
        val out = File(destDir, src.name)
        if (src.isDirectory) src.listFiles().forEach { copyRec(it, out, fs) }
        else java.io.BufferedInputStream(UsbFileInputStream(src), fs.chunkSize).use { i -> out.outputStream().use { i.copyTo(it, fs.chunkSize) } }
    }

    // ---------- rename ----------
    private fun rename(dev: UsbMassStorageDevice, b: BlockDeviceDriver) {
        val input = EditText(this).apply { hint = "MBDRIVE"; setSingleLine(); filters = arrayOf(android.text.InputFilter.LengthFilter(11)) }
        MaterialAlertDialogBuilder(this).setTitle(R.string.usb_rename).setMessage(R.string.usb_rename_hint)
            .setView(FrameLayout(this).apply { setPadding(px(22), 0, px(22), 0); addView(input) })
            .setPositiveButton(R.string.rename) { _, _ ->
                val label = input.text.toString().trim()
                if (label.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            val mbr = java.nio.ByteBuffer.allocate(b.blockSize).order(java.nio.ByteOrder.LITTLE_ENDIAN); b.read(0, mbr)
                            val a = mbr.array()
                            val isBoot = String(a, 82, 5) == "FAT32"
                            val start = if (isBoot) 0L else mbr.getInt(446 + 8).toLong() and 0xFFFFFFFFL
                            FatFormatter.setLabel(b, start, label)
                        }
                    }
                    Toast.makeText(this@UsbToolsActivity, r.fold({ getString(R.string.usb_renamed) }, { getString(R.string.failed, it.message ?: "") }), Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    // ---------- format ----------
    private fun askFormat(dev: UsbMassStorageDevice, b: BlockDeviceDriver, name: String) {
        val input = EditText(this).apply { setText("USB"); setSingleLine(); filters = arrayOf(android.text.InputFilter.LengthFilter(11)) }
        MaterialAlertDialogBuilder(this).setTitle(R.string.usb_format)
            .setMessage(getString(R.string.usb_format_warning, name))
            .setView(FrameLayout(this).apply { setPadding(px(22), 0, px(22), 0); addView(input) })
            .setPositiveButton(R.string.usb_format_now) { _, _ ->
                MaterialAlertDialogBuilder(this).setTitle(R.string.are_you_sure).setMessage(R.string.usb_format_confirm2)
                    .setPositiveButton(R.string.usb_format_now) { _, _ -> doFormat(b, input.text.toString().ifBlank { "USB" }) }
                    .setNegativeButton(R.string.cancel, null).show()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun doFormat(b: BlockDeviceDriver, label: String) {
        val bar = LinearProgressIndicator(this).apply { max = 100 }
        val pct = text("0%", 13f).apply { gravity = Gravity.END }
        val dlg = MaterialAlertDialogBuilder(this).setTitle(R.string.usb_formatting)
            .setView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(24), px(8), px(24), 0); addView(bar); addView(pct) })
            .setCancelable(false).show()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { FatFormatter.format(b, label) { p -> runOnUiThread { bar.progress = p; pct.text = "$p%" } } }
            }
            dlg.dismiss()
            MaterialAlertDialogBuilder(this@UsbToolsActivity)
                .setTitle(if (r.isSuccess) R.string.usb_format_done else R.string.usb_format_failed)
                .setMessage(r.fold({ getString(R.string.usb_replug) }, { it.message ?: "" }))
                .setPositiveButton(R.string.ok) { _, _ -> render() }.show()
        }
    }

    // ---------- root: mount NTFS / exFAT ----------
    private fun mountWithRoot() {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                if (!RootShell.requestRoot()) return@withContext getString(R.string.root_denied)
                val all = RootShell.run("ls /dev/block/ | grep -E '^sd[a-z][0-9]*$'").out.lines().filter { it.isNotBlank() }
                // use partitions (sda1…); a disk without partitions is used whole (sda)
                val parts = all.filter { it.last().isDigit() } + all.filter { d -> !d.last().isDigit() && all.none { it != d && it.startsWith(d) } }
                if (parts.isEmpty()) return@withContext getString(R.string.usb_none)
                val sb = StringBuilder()
                parts.forEach { p ->
                    val mp = "/mnt/mbfm/$p"
                    val cmd = "mkdir -p $mp; " +
                        "mount -t ntfs3 -o rw,umask=0 /dev/block/$p $mp 2>/dev/null || " +
                        "ntfs-3g /dev/block/$p $mp 2>/dev/null || " +
                        "mount -t ntfs -o ro /dev/block/$p $mp 2>/dev/null || " +
                        "mount -t exfat -o rw,umask=0 /dev/block/$p $mp 2>/dev/null || " +
                        "mount -t vfat -o rw,umask=0 /dev/block/$p $mp"
                    val res = RootShell.run(cmd)
                    sb.append(if (res.ok) "✓ $p → $mp\n" else "✗ $p\n")
                }
                sb.toString()
            }
            MaterialAlertDialogBuilder(this@UsbToolsActivity).setTitle(R.string.usb_mount_root)
                .setMessage(r + "\n" + getString(R.string.usb_mount_open_hint))
                .setPositiveButton(R.string.ok, null).show()
        }
    }

    companion object { const val ACTION_PERM = "com.mbnetwork.filemanager.USB_PERMISSION" }
}
