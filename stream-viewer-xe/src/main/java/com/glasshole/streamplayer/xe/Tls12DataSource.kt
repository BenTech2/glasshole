package com.glasshole.streamplayer.xe

import android.net.Uri
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.BaseDataSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * ExoPlayer DataSource backed by OkHttp.
 * Relies on Conscrypt being installed as the security provider (done in App.kt)
 * so TLS 1.2+ works on API 19.
 */
class Tls12DataSource(private val client: OkHttpClient) : BaseDataSource(true) {

    private var response: Response? = null
    private var inputStream: InputStream? = null

    override fun open(dataSpec: DataSpec): Long {
        val requestBuilder = Request.Builder()
            .url(dataSpec.uri.toString())
            .addHeader("User-Agent", "Mozilla/5.0")

        if (dataSpec.position != 0L) {
            requestBuilder.addHeader("Range", "bytes=${dataSpec.position}-")
        }

        val resp = client.newCall(requestBuilder.build()).execute()
        response = resp

        if (!resp.isSuccessful) {
            throw java.io.IOException("HTTP ${resp.code()}: ${resp.message()}")
        }

        val body = resp.body() ?: throw java.io.IOException("Empty response body")
        inputStream = body.byteStream()

        val contentLength = body.contentLength()
        return if (contentLength > 0) contentLength else C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val stream = inputStream ?: return C.RESULT_END_OF_INPUT
        val read = stream.read(buffer, offset, length)
        if (read == -1) return C.RESULT_END_OF_INPUT
        return read
    }

    override fun getUri(): Uri? = response?.request()?.url()?.let { Uri.parse(it.toString()) }

    override fun close() {
        inputStream?.close()
        inputStream = null
        response?.close()
        response = null
    }

    class Factory : DataSource.Factory {
        private val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        override fun createDataSource(): DataSource = Tls12DataSource(client)
    }
}
