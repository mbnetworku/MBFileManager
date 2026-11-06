package com.mbnetwork.filemanager

import java.io.File

/** Extra quick folders (Screenshots, WhatsApp Images) that are shown only when they exist on the phone. */
object QuickFolders {
    private val screenshots = listOf("Pictures/Screenshots", "DCIM/Screenshots", "Screenshots")
    private val whatsapp = listOf(
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images", "WhatsApp/Media/WhatsApp Images",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images")

    fun extras(internal: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        screenshots.firstOrNull { File(internal, it).isDirectory }?.let { out += R.string.screenshots to it }
        whatsapp.firstOrNull { File(internal, it).isDirectory }?.let { out += R.string.whatsapp_images to it }
        return out
    }
}
