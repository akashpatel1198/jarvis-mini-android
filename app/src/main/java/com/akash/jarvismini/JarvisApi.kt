package com.akash.jarvismini

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

// Dev: laptop's LAN IP, server running on port 8000.
// Replace with your laptop's actual IP if it changes (different wifi, etc.).
private const val SERVER_URL = "http://192.168.1.27:8000"

data class CommandResponse(val transcript: String, val reply: String)

class JarvisApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun command(audioFile: File): CommandResponse {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "audio",
                filename = audioFile.name,
                body = audioFile.asRequestBody("audio/mp4".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url("$SERVER_URL/command")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: $payload")
            }
            val json = JSONObject(payload)
            return CommandResponse(
                transcript = json.getString("transcript"),
                reply = json.getString("reply"),
            )
        }
    }
}
