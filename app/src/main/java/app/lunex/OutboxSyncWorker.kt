package app.lunex

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Schedules delivery of locally queued messages when Android reports a network.
 * WorkManager owns retries; the server's clientMessageId handling makes retries idempotent.
 */
internal object OutboxSyncScheduler {
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<OutboxSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "lunex-outbox-sync",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }
}

internal class OutboxSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("lunex", Context.MODE_PRIVATE)
        val base = (prefs.getString("server", "https://lunex-messenger.onrender.com")
            ?: "https://lunex-messenger.onrender.com").trim().trimEnd('/')
        val token = prefs.getString("token", "").orEmpty()
        if (token.isBlank()) return@withContext Result.failure()

        val outbox = OfflineOutbox(applicationContext)
        for (pending in outbox.pending()) {
            val encodedId = URLEncoder.encode(pending.targetId, "UTF-8")
            val endpoint = when (pending.kind) {
                "dm" -> "/api/dms/$encodedId/messages"
                "channel" -> "/api/messages/$encodedId"
                else -> {
                    // Unknown queue entries must not block valid messages behind them.
                    outbox.acknowledge(pending.clientId)
                    continue
                }
            }
            var connection: HttpURLConnection? = null
            try {
                val body = JSONObject()
                    .put("clientMessageId", pending.clientId)
                    .put("text", pending.text)
                connection = (URL(base + endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12000
                    readTimeout = 12000
                    doOutput = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $token")
                    outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status in 200..299) {
                    val response = JSONObject(raw)
                    val message = response.optJSONObject("message")
                        ?: return@withContext Result.retry()
                    outbox.acknowledge(pending.clientId)
                    outbox.store(
                        StoredMessage(
                            id = message.optString("id"),
                            kind = pending.kind,
                            targetId = pending.targetId,
                            user = message.optString("user"),
                            userId = message.optString("userId"),
                            text = message.optString("text"),
                            time = message.optString("time")
                        )
                    )
                } else if (status == 408 || status == 429 || status >= 500) {
                    return@withContext Result.retry()
                } else {
                    // Keep the item in SQLite. A later login/retry can recover from
                    // expired credentials or a temporarily unavailable conversation.
                    return@withContext Result.failure()
                }
            } catch (_: Exception) {
                return@withContext Result.retry()
            } finally {
                connection?.disconnect()
            }
        }
        Result.success()
    }
}
