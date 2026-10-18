package com.novastreams.app.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.buffer
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Ответ сервера: код, тело как текст. Тело читается целиком, соединение закрывается. */
data class HttpResult(val code: Int, val body: String) {
    val isSuccess: Boolean get() = code in 200..299

    fun json(): JSONObject = try {
        JSONObject(body)
    } catch (e: Exception) {
        JSONObject()
    }
}

/**
 * Общий «кран» скорости для фоновых запросов чата (смайлики, история): суммарно не больше [BYTES_PER_SEC],
 * чтобы чат не отнимал канал у видео. Видео и остальные запросы не затрагиваются.
 */
object ChatThrottle {
    /** ≈1,2 Мбит/с на всё, что относится к чату. Подбирается по месту: меньше — смайлики грузятся медленнее. */
    const val BYTES_PER_SEC = 150_000L
    const val HEADER = "X-Nova-Throttle"

    private var nextFreeNs = 0L

    /** Резервирует время на [bytes] и возвращает, сколько миллисекунд подождать до отдачи данных. */
    @Synchronized
    private fun reserveMs(bytes: Long): Long {
        val now = System.nanoTime()
        val start = maxOf(now, nextFreeNs)
        nextFreeNs = start + bytes * 1_000_000_000L / BYTES_PER_SEC
        return (start - now) / 1_000_000L
    }

    fun throttle(body: okhttp3.ResponseBody): okhttp3.ResponseBody = object : okhttp3.ResponseBody() {
        private val buffered = object : okio.ForwardingSource(body.source()) {
            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                val n = super.read(sink, minOf(byteCount, 8_192L))
                if (n > 0) {
                    val wait = reserveMs(n)
                    if (wait > 0) {
                        try { Thread.sleep(wait) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    }
                }
                return n
            }
        }.buffer()

        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun source() = buffered
    }

    /** Ограничивает только запросы с заголовком [HEADER]; сам заголовок на сервер не уходит. */
    val interceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        if (request.header(HEADER) == null) {
            chain.proceed(request)
        } else {
            val response = chain.proceed(request.newBuilder().removeHeader(HEADER).build())
            val body = response.body
            if (body == null) response else response.newBuilder().body(throttle(body)).build()
        }
    }
}

/** Общий OkHttp-клиент приложения. */
object Http {
    /** Обычные HTTP-запросы. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(ChatThrottle.interceptor)
        // На медленной сети 15–20 с часто мало: ответ ещё идёт, а клиент уже сдаётся.
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Клиент только для чата (WebSocket).
     * У [client] callTimeout/readTimeout рвут долгий IRC-сокет:
     * тишина в чате > 45 с или сессия > 60 с → onFailure → вечные «переподключения», сообщения не копятся.
     */
    val wsClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Без лимита простоя: в «тихом» чате минутами нет PRIVMSG — иначе OkHttp рвёт сокет.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        // WebSocket ping, чтобы NAT/роутер не убивал TCP, когда в чате никто не пишет.
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun execute(request: Request): HttpResult = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = try {
                        it.body?.string().orEmpty()
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                        return
                    }
                    if (cont.isActive) cont.resume(HttpResult(it.code, text))
                }
            }
        })
    }

    suspend fun postForm(url: String, fields: Map<String, String>): HttpResult {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return execute(Request.Builder().url(url).post(body).build())
    }
}
