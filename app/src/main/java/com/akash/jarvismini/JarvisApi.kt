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

// Set `serverUrl=http://...` in local.properties (gitignored).
private val SERVER_URL = BuildConfig.SERVER_URL

data class CommandResponse(
    val transcript: String,
    val reply: String,
    val phoneActions: List<PhoneAction>,
)

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
            val actionsArray = json.optJSONArray("phone_actions")
            val actions = buildList {
                if (actionsArray != null) {
                    for (i in 0 until actionsArray.length()) {
                        add(PhoneAction.fromJson(actionsArray.getJSONObject(i)))
                    }
                }
            }
            return CommandResponse(
                transcript = json.getString("transcript"),
                reply = json.getString("reply"),
                phoneActions = actions,
            )
        }
    }
}
