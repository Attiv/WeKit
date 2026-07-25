package dev.ujhhgtg.wekit.features.api.net.abc

import dev.ujhhgtg.wekit.features.api.net.models.SignResult
import org.json.JSONObject

interface ISigner {
    fun match(cgiId: Int): Boolean
    fun matchProto(value: Any): Boolean = false
    fun sign(cl: ClassLoader, json: JSONObject): SignResult
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> preprocessProto(value: T): T = value
}
