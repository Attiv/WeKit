package dev.ujhhgtg.wekit.features.api.net.models

import org.json.JSONObject

data class SignResult(
    val json: JSONObject? = null,
    val protoBytes: ByteArray? = null,
    val nativeNetScene: Any? = null,
    val onSendSuccess: (() -> Unit)? = null
)
