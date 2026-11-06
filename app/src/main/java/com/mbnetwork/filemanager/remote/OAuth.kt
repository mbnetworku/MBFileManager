package com.mbnetwork.filemanager.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * Sign-in for Google Drive, OneDrive and Dropbox (OAuth with PKCE).
 * The browser sends you back to http://localhost:53682/ - the app listens there for a moment to catch the sign-in code.
 * You use your OWN app keys (Client ID); see docs/CLOUD_SETUP.md. Nothing is sent anywhere except to the cloud service itself.
 */
object OAuth {
    const val PORT = 53682
    const val REDIRECT = "http://localhost:$PORT/"

    class Provider(
        val id: String, val title: String, val authUrl: String, val tokenUrl: String, val scope: String,
        val needsSecret: Boolean, val extraAuth: Map<String, String>, val scopeOnToken: Boolean
    )

    val DROPBOX = Provider("dropbox", "Dropbox", "https://www.dropbox.com/oauth2/authorize", "https://api.dropboxapi.com/oauth2/token",
        "", false, mapOf("token_access_type" to "offline"), false)
    val ONEDRIVE = Provider("onedrive", "OneDrive", "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        "https://login.microsoftonline.com/common/oauth2/v2.0/token", "Files.ReadWrite offline_access", false, emptyMap(), true)
    val GDRIVE = Provider("gdrive", "Google Drive", "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
        "https://www.googleapis.com/auth/drive", true, mapOf("access_type" to "offline", "prompt" to "consent"), false)

    val all = listOf(GDRIVE, ONEDRIVE, DROPBOX)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE)

    fun clientId(ctx: Context, p: Provider): String = sp(ctx).getString("cid_${p.id}", "") ?: ""
    fun clientSecret(ctx: Context, p: Provider): String = Secrets.dec(sp(ctx).getString("csec_${p.id}", "") ?: "")

    fun saveKeys(ctx: Context, p: Provider, id: String, secret: String) {
        sp(ctx).edit().putString("cid_${p.id}", id.trim()).putString("csec_${p.id}", Secrets.enc(secret.trim())).apply()
    }

    private fun random(n: Int): String {
        val b = ByteArray(n); SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Opens the browser, waits for the answer, and gives back the new connection (or an error text). */
    fun signIn(act: Activity, p: Provider, cb: (Conn?, String?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val cid = clientId(act, p)
        if (cid.isEmpty()) { cb(null, "Enter your ${p.title} Client ID first."); return }
        if (!Secrets.available()) { cb(null, "Cloud accounts need Android 6 or newer."); return }
        val verifier = random(48)
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val state = random(12)
        val b = Uri.parse(p.authUrl).buildUpon()
            .appendQueryParameter("client_id", cid).appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT).appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256").appendQueryParameter("state", state)
        if (p.scope.isNotEmpty()) b.appendQueryParameter("scope", p.scope)
        p.extraAuth.forEach { (k, v) -> b.appendQueryParameter(k, v) }
        val authUri = b.build()

        Thread {
            try {
                var code: String? = null
                var err: String? = null
                ServerSocket(PORT, 5, InetAddress.getByName("127.0.0.1")).use { ss ->
                    ss.soTimeout = 300_000
                    main.post { act.startActivity(Intent(Intent.ACTION_VIEW, authUri)) }
                    val end = System.currentTimeMillis() + 300_000
                    while (code == null && err == null && System.currentTimeMillis() < end) {
                        val s = ss.accept()
                        s.soTimeout = 5000
                        try {
                            val line = s.getInputStream().bufferedReader().readLine() ?: continue
                            val q = Uri.parse("http://localhost" + line.split(" ").getOrElse(1) { "/" })
                            val c = q.getQueryParameter("code")
                            val e = q.getQueryParameter("error")
                            if (c != null || e != null) {
                                if (q.getQueryParameter("state") != state) err = "Sign-in was rejected (state mismatch)."
                                else if (e != null) err = e else code = c
                                val msg = if (code != null) "Signed in. You can go back to MB File Manager now." else "Sign-in failed. You can close this page."
                                val html = "<html><head><meta name='viewport' content='width=device-width'></head><body style='font-family:sans-serif;padding:32px'><h2>$msg</h2></body></html>"
                                s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n$html").toByteArray())
                            }
                        } catch (t: java.net.SocketTimeoutException) { } finally { try { s.close() } catch (t: Throwable) { } }
                    }
                }
                if (err != null) throw IOException(err)
                val authCode = code ?: throw IOException("Sign-in timed out.")
                val form = ArrayList<Pair<String, String>>()
                form += "grant_type" to "authorization_code"; form += "code" to authCode; form += "redirect_uri" to REDIRECT
                form += "client_id" to cid; form += "code_verifier" to verifier
                if (p.needsSecret) form += "client_secret" to clientSecret(act, p)
                if (p.scopeOnToken) form += "scope" to p.scope
                val j = Http.json(Http.run(Request.Builder().url(p.tokenUrl).post(Http.form(*form.toTypedArray())).build()))
                val refresh = j.optString("refresh_token")
                if (refresh.isEmpty()) throw IOException("No refresh token received. Check the app settings (offline access).")
                val conn = Conn(UUID.randomUUID().toString(), p.id, p.title, "", 0, "", Secrets.enc(refresh), "")
                main.post { cb(conn, null) }
            } catch (t: Throwable) {
                main.post { cb(null, t.message ?: t.javaClass.simpleName) }
            }
        }.start()
    }
}

/** Gives a valid access token for a saved cloud account (refreshes it when it has expired). */
class TokenSource(private val ctx: Context, private val p: OAuth.Provider, private var conn: Conn) {
    private var access = ""
    private var expires = 0L

    @Synchronized
    fun get(): String {
        if (access.isEmpty() || System.currentTimeMillis() > expires - 60_000) refresh()
        return access
    }

    private fun refresh() {
        val rt = Secrets.dec(conn.pass)
        if (rt.isEmpty()) throw IOException("Please sign in to ${p.title} again.")
        val form = ArrayList<Pair<String, String>>()
        form += "grant_type" to "refresh_token"; form += "refresh_token" to rt; form += "client_id" to OAuth.clientId(ctx, p)
        if (p.needsSecret) form += "client_secret" to OAuth.clientSecret(ctx, p)
        if (p.scopeOnToken) form += "scope" to p.scope
        val j = Http.json(Http.run(Request.Builder().url(p.tokenUrl).post(Http.form(*form.toTypedArray())).build()))
        access = j.getString("access_token")
        expires = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000
        val nr = j.optString("refresh_token")
        if (nr.isNotEmpty() && nr != rt) { conn = conn.copy(pass = Secrets.enc(nr)); ConnStore.save(ctx, conn) }
    }
}
