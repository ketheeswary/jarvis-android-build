package com.example.jarvis

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class JarvisConfig(val baseUrl: String, val appToken: String, val picovoiceKey: String)
data class ActionTicket(val id: String, val kind: String, val target: String, val operation: String, val label: String)
data class ChatResult(val reply: String, val action: ActionTicket?)

object ApiClient {
    // Execute via Dispatchers.IO, never on the Android UI thread.
    private fun request(config: JarvisConfig, method: String, path: String, body: JSONObject? = null): JSONObject {
        val base = config.baseUrl.trim().trimEnd('/')
        if (!base.startsWith("https://") && !base.startsWith("http://")) {
            throw IOException("Enter a complete server URL (http:// for trusted LAN debug, or https://)")
        }
        val connection = (URL(base + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 8000
            connection.readTimeout = 60000
            connection.setRequestProperty("Authorization", "Bearer ${config.appToken}")
            connection.setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            if (connection.responseCode !in 200..299) {
                val detail = runCatching { JSONObject(raw).optString("detail") }.getOrDefault(raw.take(140))
                throw IOException("Server ${connection.responseCode}: $detail")
            }
            return JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }

    fun chat(config: JarvisConfig, deviceId: String, message: String): ChatResult {
        val json = request(config, "POST", "/chat", JSONObject().put("device_id", deviceId).put("message", message))
        val pending = json.optJSONObject("action")?.let {
            ActionTicket(it.getString("id"), it.getString("kind"), it.getString("target"),
                it.optString("operation"), it.getString("label"))
        }
        return ChatResult(json.getString("reply"), pending)
    }

    fun execute(config: JarvisConfig, deviceId: String, actionId: String): JSONObject =
        request(config, "POST", "/execute", JSONObject().put("device_id", deviceId).put("action_id", actionId))

    fun eraseMemory(config: JarvisConfig, deviceId: String): Boolean =
        request(config, "DELETE", "/memory/$deviceId").optBoolean("ok")
}
