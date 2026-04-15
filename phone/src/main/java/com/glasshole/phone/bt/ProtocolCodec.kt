package com.glasshole.phone.bt

/**
 * Encodes and decodes the GlassHole Bluetooth protocol.
 *
 * Protocol format (newline-delimited):
 *   PING / PONG                                - Heartbeat
 *   MSG:<text>                                  - Notification forwarding
 *   REPLY:<text>                                - Glass voice reply
 *   INFO_REQ / INFO:<json>                      - Glass device info
 *   INSTALL:<filename>:<size>:<md5>             - Begin APK transfer
 *   INSTALL_DATA:<base64chunk>                  - APK data chunk
 *   INSTALL_END / INSTALL_ACK:<status>          - Transfer complete
 *   PLUGIN:<pluginId>:<messageType>:<payload>   - Plugin message
 *   NOTIF_ACTION:<json>                         - Invoke a notification action
 *       json = {"key":"...","id":"...","text":"..."}
 */
object ProtocolCodec {

    fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\n", "\\n")

    fun unescape(text: String): String =
        text.replace("\\n", "\n").replace("\\\\", "\\")

    // --- Encoding ---

    fun encodePlugin(pluginId: String, type: String, payload: String): String =
        "PLUGIN:$pluginId:$type:${escape(payload)}\n"

    fun encodeMsg(text: String): String =
        "MSG:${escape(text)}\n"

    /**
     * Rich notification with fields and optional base64 icon.
     * Payload is a JSON object: {app, title, text, icon?}
     */
    fun encodeNotif(json: String): String =
        "NOTIF:${escape(json)}\n"

    fun encodeReply(text: String): String =
        "REPLY:${escape(text)}\n"

    fun encodeInfo(json: String): String =
        "INFO:$json\n"

    fun encodeInfoReq(): String = "INFO_REQ\n"

    fun encodePing(): String = "PING\n"
    fun encodePong(): String = "PONG\n"

    fun encodeInstallStart(filename: String, size: Long, md5: String): String =
        "INSTALL:$filename:$size:$md5\n"

    fun encodeInstallData(base64Chunk: String): String =
        "INSTALL_DATA:$base64Chunk\n"

    fun encodeInstallEnd(): String = "INSTALL_END\n"

    fun encodeInstallAck(status: String): String =
        "INSTALL_ACK:$status\n"

    fun encodeListPackagesReq(): String = "LIST_PACKAGES_REQ\n"

    fun encodeListPackages(json: String): String = "LIST_PACKAGES:$json\n"

    fun encodeUninstall(pkg: String): String = "UNINSTALL:$pkg\n"

    fun encodeUninstallAck(pkg: String, status: String): String =
        "UNINSTALL_ACK:$pkg:$status\n"

    fun encodeNotifAction(notifKey: String, actionId: String, replyText: String? = null): String {
        val obj = org.json.JSONObject().apply {
            put("key", notifKey)
            put("id", actionId)
            if (replyText != null) put("text", replyText)
        }
        return "NOTIF_ACTION:${escape(obj.toString())}\n"
    }

    // --- Decoding ---

    fun decode(line: String): DecodedMessage {
        return when {
            line.startsWith("PLUGIN:") -> {
                val content = line.removePrefix("PLUGIN:")
                val parts = content.split(":", limit = 3)
                if (parts.size >= 2) {
                    DecodedMessage.Plugin(
                        pluginId = parts[0],
                        type = parts[1],
                        payload = unescape(parts.getOrElse(2) { "" })
                    )
                } else {
                    DecodedMessage.Unknown(line)
                }
            }
            line.startsWith("NOTIF:") -> DecodedMessage.RichNotif(unescape(line.removePrefix("NOTIF:")))
            line.startsWith("MSG:") -> DecodedMessage.Notification(unescape(line.removePrefix("MSG:")))
            line.startsWith("REPLY:") -> DecodedMessage.Reply(unescape(line.removePrefix("REPLY:")))
            line.startsWith("INFO:") -> DecodedMessage.Info(line.removePrefix("INFO:"))
            line.startsWith("INSTALL_ACK:") -> DecodedMessage.InstallAck(line.removePrefix("INSTALL_ACK:"))
            line.startsWith("INSTALL:") -> {
                val parts = line.removePrefix("INSTALL:").split(":", limit = 3)
                if (parts.size == 3) {
                    DecodedMessage.InstallStart(parts[0], parts[1].toLongOrNull() ?: 0, parts[2])
                } else {
                    DecodedMessage.Unknown(line)
                }
            }
            line.startsWith("INSTALL_DATA:") -> DecodedMessage.InstallData(line.removePrefix("INSTALL_DATA:"))
            line == "INSTALL_END" -> DecodedMessage.InstallEnd
            line.startsWith("LIST_PACKAGES:") -> DecodedMessage.ListPackages(line.removePrefix("LIST_PACKAGES:"))
            line == "LIST_PACKAGES_REQ" -> DecodedMessage.ListPackagesReq
            line.startsWith("UNINSTALL_ACK:") -> {
                val rest = line.removePrefix("UNINSTALL_ACK:").split(":", limit = 2)
                DecodedMessage.UninstallAck(rest[0], rest.getOrElse(1) { "" })
            }
            line.startsWith("UNINSTALL:") -> DecodedMessage.Uninstall(line.removePrefix("UNINSTALL:"))
            line.startsWith("NOTIF_ACTION:") -> {
                val json = unescape(line.removePrefix("NOTIF_ACTION:"))
                try {
                    val obj = org.json.JSONObject(json)
                    DecodedMessage.NotifAction(
                        notifKey = obj.optString("key"),
                        actionId = obj.optString("id"),
                        replyText = if (obj.has("text")) obj.optString("text") else null
                    )
                } catch (_: Exception) {
                    DecodedMessage.Unknown(line)
                }
            }
            line == "PING" -> DecodedMessage.Ping
            line == "PONG" -> DecodedMessage.Pong
            line == "INFO_REQ" -> DecodedMessage.InfoReq
            else -> DecodedMessage.Unknown(line)
        }
    }
}

sealed class DecodedMessage {
    data class Plugin(val pluginId: String, val type: String, val payload: String) : DecodedMessage()
    data class Notification(val text: String) : DecodedMessage()
    data class RichNotif(val json: String) : DecodedMessage()
    data class Reply(val text: String) : DecodedMessage()
    data class Info(val json: String) : DecodedMessage()
    object Ping : DecodedMessage()
    object Pong : DecodedMessage()
    object InfoReq : DecodedMessage()
    data class InstallStart(val filename: String, val size: Long, val md5: String) : DecodedMessage()
    data class InstallData(val base64Chunk: String) : DecodedMessage()
    object InstallEnd : DecodedMessage()
    data class InstallAck(val status: String) : DecodedMessage()
    object ListPackagesReq : DecodedMessage()
    data class ListPackages(val json: String) : DecodedMessage()
    data class Uninstall(val pkg: String) : DecodedMessage()
    data class UninstallAck(val pkg: String, val status: String) : DecodedMessage()
    data class NotifAction(
        val notifKey: String,
        val actionId: String,
        val replyText: String?
    ) : DecodedMessage()
    data class Unknown(val raw: String) : DecodedMessage()
}
