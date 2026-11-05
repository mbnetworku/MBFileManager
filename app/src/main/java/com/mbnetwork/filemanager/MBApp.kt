package com.mbnetwork.filemanager

import android.app.Application
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Hard block for the internet. While "Block internet" is on, every HTTP / HTTPS connection made by this app is sent to a
 * dead proxy and fails, even if some code forgot to check the switch.
 */
class MBApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val original = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?): MutableList<Proxy> =
                if (Net.blocked(this@MBApp)) mutableListOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", 9)))
                else original?.select(uri) ?: mutableListOf(Proxy.NO_PROXY)

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                original?.connectFailed(uri, sa, ioe)
            }
        })
    }
}
