package dev.ujhhgtg.wekit.features.api.net.abc

import dev.ujhhgtg.wekit.features.api.net.models.SignResult
import org.json.JSONObject

interface ISigner {
    fun matchesJson(cgiId: Int): Boolean
    fun matchesProto(value: Any): Boolean = false
    fun preprocessJson(cl: ClassLoader, json: JSONObject): SignResult
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> preprocessProto(value: T): T = value
}
