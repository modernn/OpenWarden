package com.openwarden.parent.android.policy

import com.openwarden.parent.android.demo.DEMO_CHILD_BASE_URL
import com.openwarden.parent.policy.PolicyPostResult
import com.openwarden.parent.policy.PolicyTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable

/**
 * DEMO-grade [PolicyTransport] (ADR-034 D5): POSTs the signed bundle JSON to the child `/policy`
 * (hardcoded demo URL until real mDNS + pinned TLS land — #21/#23; authenticity is the signature,
 * orthogonal to transport confidentiality per ADR-030/031). Maps the child response:
 * `200 {"status":"applied","policy_seq"}` → [PolicyPostResult.Applied];
 * `400 {"error","reason"}` → [PolicyPostResult.Rejected]; any network/parse failure →
 * [PolicyPostResult.TransportError] (fail-closed; the bundle is NOT marked sent).
 */
class KtorPolicyTransport(
    private val baseUrl: String = DEMO_CHILD_BASE_URL,
) : PolicyTransport,
    Closeable {
    private val parser =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    private val http = HttpClient(OkHttp)

    override suspend fun postPolicy(bundleJson: String): PolicyPostResult =
        runCatching {
            val response =
                http.post("$baseUrl/policy") {
                    contentType(ContentType.Application.Json)
                    setBody(bundleJson)
                }
            val text = response.bodyAsText()
            if (response.status.isSuccess()) {
                val seq = field(text, "policy_seq")?.toLongOrNull() ?: -1L
                PolicyPostResult.Applied(seq)
            } else {
                val reason =
                    field(text, "reason") ?: field(text, "error")
                        ?: "child rejected (${response.status.value})"
                PolicyPostResult.Rejected(reason)
            }
        }.getOrElse { PolicyPostResult.TransportError(it.message ?: "transport error") }

    private fun field(
        json: String,
        key: String,
    ): String? =
        runCatching {
            parser
                .parseToJsonElement(json)
                .jsonObject[key]
                ?.jsonPrimitive
                ?.content
        }.getOrNull()

    override fun close() = http.close()
}
