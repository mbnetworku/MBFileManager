package com.mbnetwork.filemanager.remote

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared web client for WebDAV and the cloud drives. */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun run(req: Request): Response {
        val r = client.newCall(req).execute()
        if (!r.isSuccessful) {
            val b = try { r.body?.string() } catch (e: Throwable) { null }
            val code = r.code
            r.close()
            throw IOException("HTTP $code ${b?.take(200) ?: ""}".trim())
        }
        return r
    }

    fun json(r: Response): JSONObject = r.use { JSONObject(it.body?.string() ?: "{}") }

    fun jsonBody(s: String): RequestBody = s.toRequestBody("application/json; charset=utf-8".toMediaType())

    fun empty(): RequestBody = ByteArray(0).toRequestBody(null)

    fun form(vararg p: Pair<String, String>): FormBody = FormBody.Builder().apply { p.forEach { add(it.first, it.second) } }.build()
}
