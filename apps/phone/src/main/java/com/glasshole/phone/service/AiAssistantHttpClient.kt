package com.glasshole.phone.service

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Phone-side HTTP proxy for the on-glass AI Assistant plugin. Glass
 * sends a single ASK envelope (`{prompt, provider, <provider>_api_key,
 * <provider>_model, system_prompt, temperature_pct, max_tokens}`); this
 * client picks the right provider path and returns either the
 * response text (caller wraps in a JSON reply) or a human-readable
 * error string. All I/O blocks the calling thread — invoke from a
 * daemon thread.
 *
 * Provider notes:
 *  - OpenAI    : /v1/chat/completions, Bearer <key>
 *  - Anthropic : /v1/messages, x-api-key + anthropic-version
 *  - Gemini    : /v1beta/models/<model>:generateContent?key=…
 *
 * Each call goes through a single endpoint per provider — no streaming
 * (the BT bridge isn't a streaming transport anyway).
 */
object AiAssistantHttpClient {

    private const val TAG = "AiAssistantClient"
    private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
    private const val ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
    private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    private const val ANTHROPIC_VERSION = "2023-06-01"

    data class Result(val text: String?, val error: String?)

    fun ask(envelope: JSONObject): Result {
        val prompt = envelope.optString("prompt").trim()
        if (prompt.isEmpty()) return Result(null, "Empty prompt")
        val provider = envelope.optString("provider", "openai")
        val systemPrompt = envelope.optString("system_prompt")
        val tempPct = envelope.optInt("temperature_pct", 30).coerceIn(0, 100)
        val temperature = tempPct / 100.0
        val maxTokens = envelope.optInt("max_tokens", 400).coerceIn(50, 4000)

        return try {
            when (provider) {
                "openai" -> callOpenAi(
                    apiKey = envelope.optString("openai_api_key"),
                    model = envelope.optString("openai_model", "gpt-4o-mini"),
                    system = systemPrompt,
                    user = prompt,
                    temperature = temperature,
                    maxTokens = maxTokens,
                )
                "anthropic" -> callAnthropic(
                    apiKey = envelope.optString("anthropic_api_key"),
                    model = envelope.optString("anthropic_model", "claude-haiku-4-5-20251001"),
                    system = systemPrompt,
                    user = prompt,
                    temperature = temperature,
                    maxTokens = maxTokens,
                )
                "gemini" -> callGemini(
                    apiKey = envelope.optString("gemini_api_key"),
                    model = envelope.optString("gemini_model", "gemini-2.5-flash"),
                    system = systemPrompt,
                    user = prompt,
                    temperature = temperature,
                    maxTokens = maxTokens,
                )
                else -> Result(null, "Unknown provider: $provider")
            }
        } catch (e: Exception) {
            Log.w(TAG, "$provider call failed: ${e.message}")
            Result(null, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun callOpenAi(
        apiKey: String, model: String, system: String, user: String,
        temperature: Double, maxTokens: Int,
    ): Result {
        if (apiKey.isBlank()) return Result(null, "OpenAI API key not set")
        val messages = JSONArray().apply {
            if (system.isNotEmpty()) {
                put(JSONObject().put("role", "system").put("content", system))
            }
            put(JSONObject().put("role", "user").put("content", user))
        }
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
        }.toString()
        val (code, resp) = post(OPENAI_URL, body, mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json",
        ))
        if (code != 200) return Result(null, "OpenAI HTTP $code: ${extractErrorMessage(resp)}")
        val text = JSONObject(resp)
            .optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content")
        return if (text.isNullOrBlank()) Result(null, "Empty response from OpenAI")
        else Result(text.trim(), null)
    }

    private fun callAnthropic(
        apiKey: String, model: String, system: String, user: String,
        temperature: Double, maxTokens: Int,
    ): Result {
        if (apiKey.isBlank()) return Result(null, "Anthropic API key not set")
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            if (system.isNotEmpty()) put("system", system)
            put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", user)
            ))
        }.toString()
        val (code, resp) = post(ANTHROPIC_URL, body, mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to ANTHROPIC_VERSION,
            "Content-Type" to "application/json",
        ))
        if (code != 200) return Result(null, "Anthropic HTTP $code: ${extractErrorMessage(resp)}")
        // Response shape: { content: [ { type:"text", text: "..." }, ... ] }
        val arr = JSONObject(resp).optJSONArray("content") ?: return Result(null, "No content")
        val out = StringBuilder()
        for (i in 0 until arr.length()) {
            val block = arr.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                if (out.isNotEmpty()) out.append("\n")
                out.append(block.optString("text", ""))
            }
        }
        return if (out.isBlank()) Result(null, "Empty response from Anthropic")
        else Result(out.toString().trim(), null)
    }

    private fun callGemini(
        apiKey: String, model: String, system: String, user: String,
        temperature: Double, maxTokens: Int,
    ): Result {
        if (apiKey.isBlank()) return Result(null, "Gemini API key not set")
        val url = "$GEMINI_URL/$model:generateContent?key=$apiKey"
        val body = JSONObject().apply {
            if (system.isNotEmpty()) {
                put("systemInstruction", JSONObject().put(
                    "parts", JSONArray().put(JSONObject().put("text", system))
                ))
            }
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", user)))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", temperature)
                put("maxOutputTokens", maxTokens)
            })
        }.toString()
        val (code, resp) = post(url, body, mapOf("Content-Type" to "application/json"))
        if (code != 200) return Result(null, "Gemini HTTP $code: ${extractErrorMessage(resp)}")
        // Response: { candidates: [ { content: { parts: [ { text: "..." } ] } } ] }
        val text = JSONObject(resp)
            .optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
            ?.optString("text")
        return if (text.isNullOrBlank()) Result(null, "Empty response from Gemini")
        else Result(text.trim(), null)
    }

    private fun post(url: String, body: String, headers: Map<String, String>): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 45_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "GlassHole/1.0")
            for ((k, v) in headers) setRequestProperty(k, v)
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to text
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    /** Each provider has its own error JSON shape — try a few common
     *  paths before falling back to the raw body. */
    private fun extractErrorMessage(body: String): String {
        if (body.isBlank()) return "(empty body)"
        return try {
            val obj = JSONObject(body)
            obj.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
                ?: obj.optString("error").takeIf { it.isNotEmpty() }
                ?: obj.optString("message").takeIf { it.isNotEmpty() }
                ?: body.take(200)
        } catch (_: Exception) { body.take(200) }
    }
}
