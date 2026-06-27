package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import kotlin.io.encoding.Base64

private const val SILICON_CLOUD_API_KEY_BASE64 =
    "c2stem9ycWpnZmhlcm9ycG1vYXJnZnJocGFxd3ViemJtdnN3cWxtc3pvbnp5bXFqY2xv"
private val SILICON_CLOUD_FREE_MODELS = setOf(
    "Qwen/Qwen3-8B",
    "THUDM/GLM-4.1V-9B-Thinking",
)

class AIRequestInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val host = request.url.host

        if (host == "api.siliconflow.cn") {
            request = processSiliconCloudRequest(request)
        }

        return chain.proceed(request)
    }

    // 处理硅基流动的请求
    private fun processSiliconCloudRequest(request: Request): Request {
        val authHeader = request.header("Authorization")
        val path = request.url.encodedPath

        // 如果没有设置 api token，填入内置免费 api key
        if ((authHeader?.trim() == "Bearer" || authHeader?.trim() == "Bearer sk-") && path in listOf(
                "/v1/chat/completions",
                "/v1/models"
            )
        ) {
            val bodyJson = request.readBodyAsJson()
            val model = bodyJson?.jsonObject["model"]?.jsonPrimitiveOrNull?.content
            if (model.isNullOrEmpty() || model in SILICON_CLOUD_FREE_MODELS) {
                val apiKey = String(Base64.decode(SILICON_CLOUD_API_KEY_BASE64))
                return request.newBuilder()
                    .header("Authorization", "Bearer $apiKey")
                    .build()
            }
        }

        return request
    }
}

private fun Request.readBodyAsJson(): JsonElement? {
    val contentType = body?.contentType()
    if (contentType?.type == "application" && contentType.subtype == "json") {
        val buffer = okio.Buffer()
        buffer.use {
            body?.writeTo(it)
            return JsonInstant.parseToJsonElement(buffer.readUtf8())
        }
    }
    return null
}
