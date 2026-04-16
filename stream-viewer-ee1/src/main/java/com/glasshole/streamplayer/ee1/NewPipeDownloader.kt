package com.glasshole.streamplayer.ee1

import okhttp3.OkHttpClient
import okhttp3.RequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.util.concurrent.TimeUnit

class NewPipeDownloader private constructor(builder: OkHttpClient.Builder) : Downloader() {

    private val client: OkHttpClient = builder.readTimeout(30, TimeUnit.SECONDS).build()

    companion object {
        private var instance: NewPipeDownloader? = null

        fun getInstance(): NewPipeDownloader {
            if (instance == null) {
                instance = NewPipeDownloader(OkHttpClient.Builder())
            }
            return instance!!
        }
    }

    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        var requestBody: RequestBody? = null
        if (dataToSend != null) {
            requestBody = RequestBody.create(null, dataToSend)
        }

        val requestBuilder = okhttp3.Request.Builder()
            .method(httpMethod, requestBody)
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")

        for ((headerName, headerValueList) in headers) {
            if (headerValueList.size > 1) {
                requestBuilder.removeHeader(headerName)
                for (headerValue in headerValueList) {
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                requestBuilder.header(headerName, headerValueList[0])
            }
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (response.code() == 429) {
            response.close()
            throw org.schabi.newpipe.extractor.exceptions.ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val body = response.body()
        val responseBody = body?.string()
        val latestUrl = response.request().url().toString()

        return Response(
            response.code(),
            response.message(),
            response.headers().toMultimap(),
            responseBody,
            latestUrl
        )
    }
}
