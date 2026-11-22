package id.vern.antigravity.service

import android.content.Context
import android.os.Environment
import android.util.Log
import id.vern.antigravity.data.AvailableModelInfo
import id.vern.antigravity.data.CodeAssistTier
import id.vern.antigravity.data.ModelAvailability
import id.vern.antigravity.data.QuotaBucket
import id.vern.antigravity.data.QuotaGroup
import id.vern.antigravity.data.TierInfo
import id.vern.antigravity.data.UserQuotaSummary
import id.vern.antigravity.oauth.OAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

object CloudCodeQuotaService {
    private const val TAG = "CloudCodeQuota"
    private const val CLOUDCODE_DAILY_BASE = "https://daily-cloudcode-pa.googleapis.com"
    private const val CLOUDCODE_PROD_BASE = "https://cloudcode-pa.googleapis.com"
    private val CLOUDCODE_BASES = listOf(CLOUDCODE_DAILY_BASE, CLOUDCODE_PROD_BASE)

    private const val QUOTA_PATH = "/v1internal:retrieveUserQuotaSummary"
    private const val MODELS_PATH = "/v1internal:fetchAvailableModels"
    private const val TIER_PATH = "/v1internal:loadCodeAssist"

    data class EndpointCandidate(
        val port: Int,
        val csrf: String,
        val preferredScheme: String = "http"
    )

    private val cachedSslContext: SSLContext? by lazy {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509ExtendedTrustManager() {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
            })
            SSLContext.getInstance("TLS").apply {
                init(null, trustAllCerts, SecureRandom())
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun persistEndpoint(port: Int, csrf: String, httpsPort: Int = 0, context: Context? = null) {
        try {
            val json = JSONObject().apply {
                put("port", port)
                if (httpsPort > 0) put("https_port", httpsPort)
                put("csrf_token", csrf)
            }.toString(2)

            val files = mutableListOf<File>()
            try {
                val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (dlDir != null) {
                    files.add(File(dlDir, "Andronix/.antigravity_endpoint"))
                    files.add(File(dlDir, ".antigravity_endpoint"))
                }
            } catch (e: Exception) {}

            files.add(File("/storage/emulated/0/Download/Andronix/.antigravity_endpoint"))
            files.add(File("/storage/emulated/0/Download/.antigravity_endpoint"))
            files.add(File("/sdcard/Download/Andronix/.antigravity_endpoint"))
            files.add(File("/sdcard/Download/.antigravity_endpoint"))
            files.add(File("/sdcard/.antigravity_endpoint"))
            files.add(File("/data/local/tmp/.antigravity_endpoint"))

            val userHome = System.getProperty("user.home")
            if (!userHome.isNullOrBlank()) {
                files.add(File(userHome, ".antigravity_endpoint"))
                files.add(File(userHome, "Download/.antigravity_endpoint"))
                files.add(File(userHome, ".gemini/.antigravity_endpoint"))
            }

            if (context != null) {
                files.add(File(context.filesDir, ".antigravity_endpoint"))
                files.add(File(context.filesDir, ".gemini/.antigravity_endpoint"))
                files.add(File(context.filesDir, "home/.antigravity_endpoint"))
            }

            for (f in files) {
                try {
                    f.parentFile?.mkdirs()
                    f.writeText(json, Charsets.UTF_8)
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }

    private fun persistEndpoint(port: Int, csrf: String, context: Context? = null) =
        persistEndpoint(port, csrf, 0, context)

    private fun findCurlBinary(context: Context? = null): String {
        val candidates = mutableListOf<String>()
        if (context != null) {
            candidates.add(File(context.filesDir, "usr/bin/curl").absolutePath)
            candidates.add(File(context.filesDir, "bin/curl").absolutePath)
            val parent = context.filesDir.parentFile
            if (parent != null) {
                candidates.add(File(parent, "files/usr/bin/curl").absolutePath)
            }
        }
        val prefix = System.getenv("PREFIX")
        if (!prefix.isNullOrBlank()) {
            candidates.add("$prefix/bin/curl")
        }
        candidates.add("/system/bin/curl")
        candidates.add("/vendor/bin/curl")
        candidates.add("curl")
        return candidates.firstOrNull {
            if (it == "curl") true else File(it).canExecute()
        } ?: "curl"
    }

    private fun fetchCsrfTokenFromPort(port: Int, context: Context? = null): String? {
        val schemes = listOf("https", "http")
        for (scheme in schemes) {
            try {
                val sc = cachedSslContext
                val allHostsValid = HostnameVerifier { hostname, session -> true }

                val url = URL("$scheme://127.0.0.1:$port/")
                val conn = url.openConnection()
                if (conn is HttpsURLConnection && sc != null) {
                    conn.sslSocketFactory = sc.socketFactory
                    conn.hostnameVerifier = allHostsValid
                }
                conn.connectTimeout = 2500
                conn.readTimeout = 2500
                conn.setRequestProperty("User-Agent", "antigravity")
                val html = conn.inputStream.bufferedReader().use { it.readText() }
                val tokenRegex = Regex("""\"?csrfToken\"?\s*:\s*\"([^\"]+)\"""")
                val altRegex = Regex("""\"?csrf_token\"?\s*:\s*\"([^\"]+)\"""")
                val token = tokenRegex.find(html)?.groupValues?.get(1)
                    ?: altRegex.find(html)?.groupValues?.get(1)
                if (!token.isNullOrBlank()) {
                    Log.i(TAG, "Discovered live CSRF token from $scheme://127.0.0.1:$port/: $token")
                    return token
                }
            } catch (e: Exception) {}
        }

        try {
            val curlBin = findCurlBinary(context)
            val process = ProcessBuilder(
                curlBin, "-k", "-s", "--max-time", "3",
                "https://127.0.0.1:$port/"
            ).redirectErrorStream(true).start()
            val html = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            val tokenRegex = Regex("""\"?csrfToken\"?\s*:\s*\"([^\"]+)\"""")
            val altRegex = Regex("""\"?csrf_token\"?\s*:\s*\"([^\"]+)\"""")
            val token = tokenRegex.find(html)?.groupValues?.get(1)
                ?: altRegex.find(html)?.groupValues?.get(1)
            if (!token.isNullOrBlank()) {
                Log.i(TAG, "Discovered live CSRF token via curl from port $port: $token")
                return token
            }
        } catch (e: Exception) {}

        return null
    }

    private fun getCandidateEndpoints(context: Context? = null): List<EndpointCandidate> {
        val list = mutableListOf<EndpointCandidate>()

        val candidateFiles = mutableListOf<File>()
        try {
            val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (dlDir != null) {
                candidateFiles.add(File(dlDir, "Andronix/.antigravity_endpoint"))
                candidateFiles.add(File(dlDir, ".antigravity_endpoint"))
            }
        } catch (e: Exception) {}

        candidateFiles.add(File("/storage/emulated/0/Download/Andronix/.antigravity_endpoint"))
        candidateFiles.add(File("/storage/emulated/0/Download/.antigravity_endpoint"))
        candidateFiles.add(File("/sdcard/Download/Andronix/.antigravity_endpoint"))
        candidateFiles.add(File("/sdcard/Download/.antigravity_endpoint"))
        candidateFiles.add(File("/sdcard/.antigravity_endpoint"))
        candidateFiles.add(File("/data/local/tmp/.antigravity_endpoint"))

        val userHome = System.getProperty("user.home")
        if (!userHome.isNullOrBlank()) {
            candidateFiles.add(File(userHome, ".antigravity_endpoint"))
            candidateFiles.add(File(userHome, "Download/.antigravity_endpoint"))
            candidateFiles.add(File(userHome, ".gemini/.antigravity_endpoint"))
        }

        if (context != null) {
            candidateFiles.add(File(context.filesDir, ".antigravity_endpoint"))
            candidateFiles.add(File(context.filesDir, ".gemini/.antigravity_endpoint"))
            candidateFiles.add(File(context.filesDir, "home/.antigravity_endpoint"))
        }

        var candidateTokenFromFiles = ""

        for (f in candidateFiles) {
            try {
                if (f.isFile && f.canRead()) {
                    val content = f.readText(Charsets.UTF_8).trim()
                    val obj = JSONObject(content)
                    val httpPort = obj.optInt("port", 0)
                    val httpsPort = obj.optInt("https_port", 0)
                    val csrf = obj.optString("csrf_token", "").ifBlank {
                        obj.optString("csrfToken", "")
                    }
                    if (csrf.isNotBlank()) {
                        if (candidateTokenFromFiles.isBlank()) {
                            candidateTokenFromFiles = csrf
                        }
                        if (httpPort > 0) {
                            list.add(EndpointCandidate(httpPort, csrf, "http"))
                        }
                        if (httpsPort > 0) {
                            list.add(EndpointCandidate(httpsPort, csrf, "https"))
                        }
                        if (httpPort <= 0 && httpsPort <= 0) {
                            list.add(EndpointCandidate(45939, csrf, "http"))
                        }
                    }
                }
            } catch (e: Exception) {}
        }

        val logCandidates = mutableListOf<File>()
        if (context != null) {
            logCandidates.add(File(context.filesDir, "home/.gemini/antigravity-cli/cli.log"))
            logCandidates.add(File(context.filesDir, ".gemini/antigravity-cli/cli.log"))
        }
        logCandidates.add(File("/storage/emulated/0/Download/Andronix/.gemini/antigravity-cli/cli.log"))
        logCandidates.add(File("/sdcard/Download/Andronix/.gemini/antigravity-cli/cli.log"))

        val logDirs = mutableListOf(
            File("/storage/emulated/0/Download/Andronix/.gemini/antigravity-cli/log"),
            File("/storage/emulated/0/Download/Andronix/.gemini/antigravity-cli"),
            File("/sdcard/Download/Andronix/.gemini/antigravity-cli/log"),
            File("/home/altafyafai/.gemini/antigravity-cli/log"),
            File("/home/altafyafai/.gemini/antigravity-cli")
        )
        if (!userHome.isNullOrBlank()) {
            logDirs.add(File(userHome, ".gemini/antigravity-cli/log"))
            logDirs.add(File(userHome, ".gemini/antigravity-cli"))
        }
        for (ld in logDirs) {
            try {
                if (ld.isDirectory) {
                    val logs = ld.listFiles { f -> f.name.startsWith("cli") && f.name.endsWith(".log") }
                    if (logs != null) {
                        logCandidates.addAll(logs.sortedByDescending { it.lastModified() }.take(2))
                    }
                }
            } catch (e: Exception) {}
        }
        for (lf in logCandidates.distinct()) {
            try {
                if (lf.isFile && lf.canRead()) {
                    val tail = lf.readText(Charsets.UTF_8).takeLast(4096)
                    val mHttp = Regex("""port at (\d+) for HTTP""").findAll(tail).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                    val mHttps = Regex("""port at (\d+) for HTTPS""").findAll(tail).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                    val token = candidateTokenFromFiles.ifBlank { "caa720fa-9dc7-4b27-ad5e-f1af56c2e5eb" }
                    if (mHttp != null && mHttp > 0) {
                        list.add(0, EndpointCandidate(mHttp, token, "http"))
                    }
                    if (mHttps != null && mHttps > 0) {
                        list.add(0, EndpointCandidate(mHttps, token, "https"))
                    }
                }
            } catch (e: Exception) {}
        }

        val candidatePorts = listOf(45157, 45158, 45159, 45160, 45939, 43189)
        for (port in candidatePorts) {
            val liveToken = fetchCsrfTokenFromPort(port, context)
            if (!liveToken.isNullOrBlank()) {
                list.add(EndpointCandidate(port, liveToken, "http"))
                list.add(EndpointCandidate(port, liveToken, "https"))
                persistEndpoint(port, liveToken, context)
                break
            }
        }

        return list.distinct()
    }

    fun formatResetCountdown(resetTime: String?): String {
        if (resetTime.isNullOrBlank()) return "<1 minute"
        return try {
            val target = Instant.parse(resetTime)
            val now = Instant.now()
            val diffMs = Duration.between(now, target).toMillis()
            if (diffMs <= 0) return "<1 minute"
            val totalMins = diffMs / 60000
            val days = totalMins / 1440
            val hours = (totalMins % 1440) / 60
            val mins = totalMins % 60
            when {
                days > 0 && hours > 0 -> "$days days, $hours hours"
                days > 0 -> "$days days"
                hours > 0 && mins > 0 -> "$hours hours, $mins minutes"
                hours > 0 -> "$hours hours"
                mins > 0 -> "$mins minutes"
                else -> "<1 minute"
            }
        } catch (e: Exception) {
            "<1 minute"
        }
    }

    fun formatSubtext(resetTime: String?): String? {
        if (resetTime.isNullOrBlank()) return null
        return try {
            val target = Instant.parse(resetTime)
            val now = Instant.now()
            val diffMs = Duration.between(now, target).toMillis()
            if (diffMs <= 0) return "Resets in <1m"
            val totalMins = diffMs / 60000
            val days = totalMins / 1440
            val hours = (totalMins % 1440) / 60
            val mins = totalMins % 60
            val str = when {
                days > 0 && hours > 0 -> "${days}d ${hours}h"
                days > 0 -> "${days}d"
                hours > 0 && mins > 0 -> "${hours}h ${mins}m"
                hours > 0 -> "${hours}h"
                mins > 0 -> "${mins}m"
                else -> "<1m"
            }
            "Resets in $str"
        } catch (e: Exception) {
            null
        }
    }

    private fun queryLocalEndpointViaCurl(port: Int, csrf: String, rpcPath: String = "RetrieveUserQuotaSummary", requestBody: String = "{}", context: Context? = null, preferredScheme: String = "http"): String? {
        val curlBin = findCurlBinary(context)
        val schemes = if (preferredScheme == "http") listOf("http", "https") else listOf("https", "http")
        for (scheme in schemes) {
            try {
                val process = ProcessBuilder(
                    curlBin,
                    "-k", "-s",
                    "--max-time", "4",
                    "-X", "POST",
                    "-H", "Content-Type: application/json",
                    "-H", "Connect-Protocol-Version: 1",
                    "-H", "x-antigravity-csrf-token: $csrf",
                    "-H", "x-codeium-csrf-token: $csrf",
                    "-d", requestBody,
                    "$scheme://127.0.0.1:$port/exa.language_server_pb.LanguageServerService/$rpcPath"
                ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()
                if (exitCode == 0 && output.isNotBlank() && (output.contains("\"groups\"") || output.contains("\"models\"") || output.contains("\"response\""))) {
                    Log.i(TAG, "curl query succeeded for local server $scheme://127.0.0.1:$port ($rpcPath)")
                    return output
                }
            } catch (e: Exception) {}
        }
        return null
    }

    private fun orderedModelIds(mResp: JSONObject, modelsMap: JSONObject?): List<String> {
        val ids = mutableListOf<String>()
        val sorts = mResp.optJSONArray("agentModelSorts")
        if (sorts != null) {
            for (i in 0 until sorts.length()) {
                val groups = sorts.optJSONObject(i)?.optJSONArray("groups")
                if (groups != null) {
                    for (j in 0 until groups.length()) {
                        val mArr = groups.optJSONObject(j)?.optJSONArray("modelIds")
                        if (mArr != null) {
                            for (k in 0 until mArr.length()) {
                                val id = mArr.optString(k)
                                if (id.isNotBlank() && !ids.contains(id)) ids.add(id)
                            }
                        }
                    }
                }
            }
        }
        if (modelsMap != null) {
            val it = modelsMap.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (!ids.contains(k)) ids.add(k)
            }
        }
        return ids
    }

    private fun modelTitle(mObj: JSONObject?, key: String): String {
        val t = mObj?.optString("title")?.ifBlank { null }
        if (!t.isNullOrBlank()) return AgyModelsService.cleanDisplayName(t)
        val d = mObj?.optString("displayName")?.ifBlank { null }
        if (!d.isNullOrBlank() && !d.contains("-tiered", ignoreCase = true) && !d.startsWith("gemini-", ignoreCase = true)) {
            return AgyModelsService.cleanDisplayName(d)
        }
        return AgyModelsService.displayNameForId(key)
    }

    private fun modelProviderName(mObj: JSONObject?): String = when (mObj?.optString("modelProvider")) {
        "MODEL_PROVIDER_ANTHROPIC" -> "Anthropic"
        "MODEL_PROVIDER_OPENAI" -> "OpenAI"
        else -> "Google"
    }

    fun isArgonModel(id: String, title: String = ""): Boolean {
        return id.contains("argon", ignoreCase = true) || title.contains("argon", ignoreCase = true)
    }

    private fun sharedModelTitles(orderedIds: List<String>, modelsMap: JSONObject?, googleGroup: Boolean): List<String> {
        if (modelsMap == null) return emptyList()
        val titles = mutableListOf<String>()
        for (id in orderedIds) {
            if (id.endsWith("-disabled", ignoreCase = true)) continue
            if (id.startsWith("tab_", ignoreCase = true) || id.startsWith("chat_", ignoreCase = true) || id.contains("embedding", ignoreCase = true)) continue
            val mObj = modelsMap.optJSONObject(id) ?: continue
            val isGoogle = modelProviderName(mObj) == "Google"
            if (isGoogle != googleGroup) continue
            val t = modelTitle(mObj, id)
            if (googleGroup && isArgonModel(id, t)) continue
            val cleanTitle = t.replace(Regex("\\s*\\((High|Medium|Low|Default)\\)", RegexOption.IGNORE_CASE), "").trim()
            if (!titles.contains(cleanTitle)) titles.add(cleanTitle)
        }
        return titles
    }

    private fun argonModelTitles(orderedIds: List<String>, modelsMap: JSONObject?): List<String> {
        if (modelsMap == null) return emptyList()
        val titles = mutableListOf<String>()
        for (id in orderedIds) {
            if (id.endsWith("-disabled", ignoreCase = true)) continue
            if (id.startsWith("tab_", ignoreCase = true) || id.startsWith("chat_", ignoreCase = true) || id.contains("embedding", ignoreCase = true)) continue
            val mObj = modelsMap.optJSONObject(id) ?: continue
            val tag = mObj.optString("tagTitle", "")
            if (tag.equals("Unavailable", ignoreCase = true)) continue
            val isGoogle = modelProviderName(mObj) == "Google"
            if (!isGoogle) continue
            val t = modelTitle(mObj, id)
            if (!isArgonModel(id, t)) continue
            val cleanTitle = t.replace(Regex("\\s*\\((High|Medium|Low|Default)\\)", RegexOption.IGNORE_CASE), "").trim()
            if (!titles.contains(cleanTitle)) titles.add(cleanTitle)
        }
        return titles
    }

    private fun buildAvailableModels(
        orderedIds: List<String>,
        modelsMap: JSONObject?,
        geminiFraction: Double?,
        geminiReset: String?,
        argonFraction: Double?,
        argonReset: String?,
        thirdPartyFraction: Double?,
        thirdPartyReset: String?
    ): List<AvailableModelInfo> {
        if (modelsMap == null) return emptyList()
        val out = mutableListOf<AvailableModelInfo>()
        val seen = HashSet<String>()
        for (k in orderedIds) {
            if (k.isBlank() || !seen.add(k)) continue
            val mObj = modelsMap.optJSONObject(k) ?: continue
            val title = modelTitle(mObj, k)
            if (!AgyModelsService.isRealAiModel(k, title)) continue
            val tag = mObj.optString("tagTitle", "")
            val prov = modelProviderName(mObj)
            val isArgon = isArgonModel(k, title)
            val qi = mObj.optJSONObject("quotaInfo")
            val groupRem = if (isArgon) argonFraction
                else if (prov == "Google") geminiFraction
                else thirdPartyFraction
            val modelRem = if (qi != null && qi.has("remainingFraction")) qi.optDouble("remainingFraction") else null
            val rem = if (modelRem != null && groupRem != null) {
                kotlin.math.min(modelRem, groupRem)
            } else modelRem ?: groupRem
            val resetT = qi?.optString("resetTime")?.takeIf { it.isNotBlank() }
                ?: if (isArgon) argonReset
                else if (prov == "Google") geminiReset
                else thirdPartyReset
            out.add(
                AvailableModelInfo(
                    modelId = k,
                    displayName = title,
                    provider = prov,
                    remainingFraction = rem,
                    resetTime = resetT,
                    availability = if (tag.equals("Unavailable", ignoreCase = true)) ModelAvailability.UNAVAILABLE else ModelAvailability.AVAILABLE
                )
            )
        }
        return out
    }

    private fun buildPickerModels(orderedIds: List<String>, modelsMap: JSONObject?): List<CliModel> {
        val picker = mutableListOf<CliModel>()
        val seen = HashSet<String>()
        for (id in orderedIds) {
            if (id.isBlank() || !seen.add(id)) continue
            val mObj = modelsMap?.optJSONObject(id)
            val title = if (mObj != null) modelTitle(mObj, id) else id
            if (!AgyModelsService.isRealAiModel(id, title)) continue
            val tag = mObj?.optString("tagTitle", "") ?: ""
            if (tag.equals("Unavailable", ignoreCase = true)) continue
            val model = AgyModelsService.cliModelFromBackendEntry(id, mObj)
            if (model != null && AgyModelsService.isRealAiModel(model.cliId, model.displayName)) picker.add(model)
        }
        return picker
    }

    private fun parseQuotaSummaryResponse(
        respStr: String,
        modelsRespStr: String? = null,
        tier: CodeAssistTier? = null,
        modelsFetchFailed: Boolean = false,
        context: Context? = null
    ): UserQuotaSummary? {
        return try {
            val root = JSONObject(respStr)
            val json = if (root.has("response")) root.getJSONObject("response") else root

            val modelsRoot = if (!modelsRespStr.isNullOrBlank()) {
                try { JSONObject(modelsRespStr) } catch (e: Exception) { null }
            } else null
            val modelsRespObj = modelsRoot?.let { if (it.has("response")) it.getJSONObject("response") else it }
            val modelsMap = modelsRespObj?.optJSONObject("models")
            val orderedIds = if (modelsRespObj != null) orderedModelIds(modelsRespObj, modelsMap) else emptyList()

            val groupsList = mutableListOf<QuotaGroup>()
            var geminiWeeklyRem: Double? = null
            var gemini5hRem: Double? = null
            var geminiWeeklyReset: String? = null
            var gemini5hReset: String? = null
            var argonWeeklyRem: Double? = null
            var argonWeeklyReset: String? = null
            var thirdPartyWeeklyRem: Double? = null
            var thirdPartyWeeklyReset: String? = null
            var thirdParty5hRem: Double? = null
            var thirdParty5hReset: String? = null

            if (json.has("groups")) {
                val groupsArr = json.getJSONArray("groups")
                for (i in 0 until groupsArr.length()) {
                    val gObj = groupsArr.getJSONObject(i)
                    val gName = gObj.optString("displayName", "Model Quota")
                    val isArgon = gName.contains("argon", ignoreCase = true)
                    val isGemini = !isArgon && gName.contains("gemini", ignoreCase = true)
                    val is3p = gName.contains("claude", ignoreCase = true) || gName.contains("gpt", ignoreCase = true)

                    val groupSharedModels = if (isArgon) {
                        argonModelTitles(orderedIds, modelsMap)
                    } else if (isGemini) {
                        sharedModelTitles(orderedIds, modelsMap, googleGroup = true)
                    } else if (is3p) {
                        sharedModelTitles(orderedIds, modelsMap, googleGroup = false)
                    } else {
                        emptyList()
                    }

                    val bucketsList = mutableListOf<QuotaBucket>()
                    if (gObj.has("buckets")) {
                        val bArr = gObj.getJSONArray("buckets")
                        for (j in 0 until bArr.length()) {
                            val bObj = bArr.getJSONObject(j)
                            val bId = bObj.optString("bucketId", "bucket-$j")
                            val bName = bObj.optString("displayName", "Limit Remaining")
                            val bWin = bObj.optString("window", "")
                            val bReset = if (bObj.has("resetTime")) bObj.optString("resetTime") else null
                            val bDesc = bObj.optString("description", "")
                            val bRem = bObj.optDouble("remainingFraction", 0.0)
                            val bDis = bObj.optBoolean("disabled", false)
                            val bSub = formatSubtext(bReset)

                            val isBucketArgon = isArgon || bId.contains("argon", ignoreCase = true) || bName.contains("argon", ignoreCase = true)
                            if (isBucketArgon) {
                                if (bWin == "5h" || bId.contains("5h") || bName.contains("5h", ignoreCase = true) || bName.contains("Five Hour", ignoreCase = true)) {
                                    continue
                                }
                                if (bWin == "weekly" || bId.contains("weekly") || bWin.isBlank()) {
                                    argonWeeklyRem = bRem
                                    argonWeeklyReset = bReset
                                }
                            } else if (isGemini) {
                                if (bWin == "weekly" || bId.contains("weekly")) {
                                    geminiWeeklyRem = bRem
                                    geminiWeeklyReset = bReset
                                } else if (bWin == "5h" || bId.contains("5h")) {
                                    gemini5hRem = bRem
                                    gemini5hReset = bReset
                                }
                            } else if (is3p) {
                                if (bWin == "weekly" || bId.contains("weekly")) {
                                    thirdPartyWeeklyRem = bRem
                                    thirdPartyWeeklyReset = bReset
                                } else if (bWin == "5h" || bId.contains("5h")) {
                                    thirdParty5hRem = bRem
                                    thirdParty5hReset = bReset
                                }
                            }

                            bucketsList.add(
                                QuotaBucket(
                                    bucketId = bId,
                                    displayName = bName,
                                    window = bWin,
                                    resetTime = bReset,
                                    description = bDesc,
                                    remainingFraction = bRem,
                                    disabled = bDis,
                                    subtext = bSub
                                )
                            )
                        }
                    }

                    val rawDesc = gObj.optString("description", "")
                    val groupDesc = if (rawDesc.isNotBlank()) rawDesc else if (groupSharedModels.isNotEmpty()) "Models within this group: ${groupSharedModels.joinToString(", ")}" else ""
                    groupsList.add(
                        QuotaGroup(
                            displayName = gName,
                            description = groupDesc,
                            sharedModels = groupSharedModels,
                            buckets = bucketsList
                        )
                    )
                }
            }

            if (modelsMap != null) {
                val it = modelsMap.keys()
                while (it.hasNext()) {
                    val mid = it.next()
                    val mObj = modelsMap.optJSONObject(mid) ?: continue
                    val prov = modelProviderName(mObj)
                    if (prov == "Google") {
                        val isArgon = isArgonModel(mid, modelTitle(mObj, mid))
                        val qi = mObj.optJSONObject("quotaInfo")
                        if (qi != null) {
                            val rem = if (qi.has("remainingFraction")) qi.optDouble("remainingFraction") else null
                            val rt = if (qi.has("resetTime")) qi.optString("resetTime").takeIf { it.isNotBlank() } else null
                            if (isArgon) {
                                if (rem != null && (argonWeeklyRem == null || rem < argonWeeklyRem!!)) {
                                    argonWeeklyRem = rem
                                }
                                if (rt != null && argonWeeklyReset == null) {
                                    argonWeeklyReset = rt
                                }
                            } else {
                                if (rem != null && (gemini5hRem == null || rem < gemini5hRem!!)) {
                                    gemini5hRem = rem
                                }
                                if (rt != null && gemini5hReset == null) {
                                    gemini5hReset = rt
                                }
                            }
                        }
                    }
                }
            }

            val hasGeminiGroup = groupsList.any { it.displayName.contains("gemini", ignoreCase = true) && !it.displayName.contains("argon", ignoreCase = true) }
            if (!hasGeminiGroup) {
                val googleShared = sharedModelTitles(orderedIds, modelsMap, googleGroup = true)
                if (googleShared.isNotEmpty() && (gemini5hRem != null || geminiWeeklyRem != null)) {
                    val fiveHourFraction = gemini5hRem ?: geminiWeeklyRem ?: 0.0
                    val fiveHourReset = gemini5hReset
                    val weeklyFraction = geminiWeeklyRem ?: fiveHourFraction
                    val weeklyReset = geminiWeeklyReset

                    val buckets = mutableListOf<QuotaBucket>()
                    buckets.add(
                        QuotaBucket(
                            bucketId = "gemini-weekly",
                            displayName = "Weekly Limit Remaining",
                            window = "weekly",
                            resetTime = weeklyReset,
                            description = if (!weeklyReset.isNullOrBlank()) {
                                "You have used some of your weekly limit, it will fully refresh in ${formatResetCountdown(weeklyReset)}."
                            } else {
                                "Your individual tier weekly quota."
                            },
                            remainingFraction = weeklyFraction,
                            disabled = false,
                            subtext = formatSubtext(weeklyReset)
                        )
                    )
                    buckets.add(
                        QuotaBucket(
                            bucketId = "gemini-5h",
                            displayName = "Five Hour Limit Remaining",
                            window = "5h",
                            resetTime = fiveHourReset,
                            description = if (!fiveHourReset.isNullOrBlank()) {
                                "You have used some of your 5-hour limit, it will fully refresh in ${formatResetCountdown(fiveHourReset)}."
                            } else {
                                "Resets in 5 hours"
                            },
                            remainingFraction = fiveHourFraction,
                            disabled = false,
                            subtext = formatSubtext(fiveHourReset)
                        )
                    )

                    val geminiGroup = QuotaGroup(
                        displayName = "Gemini Models",
                        description = "Models within this group: ${googleShared.joinToString(", ")}",
                        sharedModels = googleShared,
                        buckets = buckets
                    )
                    groupsList.add(0, geminiGroup)
                }
            }

            val cliArgonTitles = context?.let { ctx ->
                val prefsJson = ctx.getSharedPreferences("antigravity_prefs", Context.MODE_PRIVATE)
                    .getString("ag_cli_models_json", null).orEmpty()
                val decoded = AgyModelsService.decodeCliModels(prefsJson)
                decoded.filter { isArgonModel(it.cliId, it.displayName) }.map { it.displayName }
            } ?: emptyList()

            val hasArgonGroup = groupsList.any { it.displayName.contains("argon", ignoreCase = true) }
            val argonShared = argonModelTitles(orderedIds, modelsMap)
            val isArgonSupported = hasArgonGroup || argonShared.isNotEmpty() || cliArgonTitles.isNotEmpty()

            if (!isArgonSupported) {
                groupsList.removeAll { it.displayName.contains("argon", ignoreCase = true) }
            } else if (!hasArgonGroup && (argonShared.isNotEmpty() || cliArgonTitles.isNotEmpty()) && argonWeeklyRem != null) {
                val aWeeklyFraction = argonWeeklyRem
                val aWeeklyReset = argonWeeklyReset

                val aBuckets = mutableListOf<QuotaBucket>()
                aBuckets.add(
                    QuotaBucket(
                        bucketId = "argon-weekly",
                        displayName = "Weekly Limit Remaining",
                        window = "weekly",
                        resetTime = aWeeklyReset,
                        description = if (!aWeeklyReset.isNullOrBlank()) {
                            "You have used some of your Gemini 4 Argon limit, it will fully refresh in ${formatResetCountdown(aWeeklyReset)}."
                        } else {
                            "Individual Gemini 4 Argon weekly quota."
                        },
                        remainingFraction = aWeeklyFraction,
                        disabled = false,
                        subtext = formatSubtext(aWeeklyReset)
                    )
                )

                val argonTitlesList = argonShared.ifEmpty { cliArgonTitles }
                val displayName = argonTitlesList.firstOrNull() ?: "Gemini 4 Argon"
                val argonGroup = QuotaGroup(
                    displayName = displayName,
                    description = "Models within this group: ${argonTitlesList.joinToString(", ")}",
                    sharedModels = argonTitlesList,
                    buckets = aBuckets
                )
                val insertIdx = if (groupsList.isNotEmpty()) 1.coerceAtMost(groupsList.size) else 0
                groupsList.add(insertIdx, argonGroup)
            }

            val pickerModels: List<CliModel> = if (modelsMap != null) {
                buildPickerModels(orderedIds, modelsMap)
            } else {
                if (modelsFetchFailed) {
                    Log.w(TAG, "Model list could not be fetched; no models are listed")
                }
                emptyList()
            }

            val effectiveGeminiRem = if (geminiWeeklyRem != null && gemini5hRem != null) {
                kotlin.math.min(geminiWeeklyRem!!, gemini5hRem!!)
            } else gemini5hRem ?: geminiWeeklyRem

            val effectiveThirdPartyRem = if (thirdPartyWeeklyRem != null && thirdParty5hRem != null) {
                kotlin.math.min(thirdPartyWeeklyRem!!, thirdParty5hRem!!)
            } else thirdParty5hRem ?: thirdPartyWeeklyRem

            val availableModels = buildAvailableModels(
                orderedIds, modelsMap,
                effectiveGeminiRem, gemini5hReset ?: geminiWeeklyReset,
                if (!isArgonSupported) null else argonWeeklyRem,
                if (!isArgonSupported) null else argonWeeklyReset,
                effectiveThirdPartyRem, thirdParty5hReset ?: thirdPartyWeeklyReset
            )

            val summaryDesc = if (json.has("description")) json.optString("description") else null
            if (groupsList.isNotEmpty()) {
                UserQuotaSummary(
                    groups = groupsList,
                    availableModels = availableModels,
                    description = summaryDesc,
                    modelsFetchFailed = modelsFetchFailed,
                    pickerModels = pickerModels
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse quota response: ${e.message}", e)
            null
        }
    }

    private fun queryLocalEndpointDirect(
        port: Int,
        csrf: String,
        rpcPath: String,
        requestBody: String = "{}",
        preferredScheme: String = "http"
    ): String? {
        val sc = cachedSslContext
        val allHostsValid = HostnameVerifier { _, _ -> true }
        val schemes = if (preferredScheme == "http") listOf("http", "https") else listOf("https", "http")

        for (scheme in schemes) {
            try {
                val url = URL("$scheme://127.0.0.1:$port/exa.language_server_pb.LanguageServerService/$rpcPath")
                val conn = url.openConnection() as HttpURLConnection
                if (conn is HttpsURLConnection && sc != null) {
                    conn.sslSocketFactory = sc.socketFactory
                    conn.hostnameVerifier = allHostsValid
                }
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Connect-Protocol-Version", "1")
                conn.setRequestProperty("x-antigravity-csrf-token", csrf)
                conn.setRequestProperty("x-codeium-csrf-token", csrf)

                OutputStreamWriter(conn.outputStream).use { it.write(requestBody); it.flush() }

                val code = conn.responseCode
                if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    if (body.isNotBlank() && (body.contains("\"groups\"") || body.contains("\"models\"") || body.contains("\"response\""))) {
                        Log.i(TAG, "Direct connection succeeded for local server $scheme://127.0.0.1:$port ($rpcPath)")
                        return body
                    }
                } else {
                    Log.w(TAG, "Direct local server $scheme://127.0.0.1:$port ($rpcPath) HTTP response: $code")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct local server $scheme://127.0.0.1:$port ($rpcPath) failed: ${e.message}")
            }
        }
        return null
    }

    private suspend fun fetchFromLocalLanguageServer(context: Context? = null, tier: CodeAssistTier? = null): UserQuotaSummary? {
        val candidates = getCandidateEndpoints(context)
        for (cand in candidates) {
            val port = cand.port
            var activeCsrf = cand.csrf
            val scheme = cand.preferredScheme
            var respStr: String? = null
            var modelsRespStr: String? = null

            respStr = queryLocalEndpointDirect(port, activeCsrf, "RetrieveUserQuotaSummary", "{}", scheme)

            if (respStr.isNullOrBlank() || !respStr.contains("\"groups\"")) {
                respStr = queryLocalEndpointViaCurl(port, activeCsrf, "RetrieveUserQuotaSummary", "{}", context, scheme)
            }

            if (respStr.isNullOrBlank() || !respStr.contains("\"groups\"")) {
                val liveCsrf = fetchCsrfTokenFromPort(port, context)
                if (!liveCsrf.isNullOrBlank() && liveCsrf != activeCsrf) {
                    activeCsrf = liveCsrf
                    persistEndpoint(port, liveCsrf, context = context)
                    respStr = queryLocalEndpointDirect(port, activeCsrf, "RetrieveUserQuotaSummary", "{}", scheme)
                        ?: queryLocalEndpointViaCurl(port, activeCsrf, "RetrieveUserQuotaSummary", "{}", context, scheme)
                }
            }

            if (!respStr.isNullOrBlank() && respStr.contains("\"groups\"")) {
                modelsRespStr = queryLocalEndpointDirect(port, activeCsrf, "GetAvailableModels", "{\"forceRefresh\": true}", scheme)

                if (modelsRespStr.isNullOrBlank()) {
                    modelsRespStr = queryLocalEndpointViaCurl(port, activeCsrf, "GetAvailableModels", "{\"forceRefresh\": true}", context, scheme)
                }

                var modelsFetchFailed = false
                if (modelsRespStr.isNullOrBlank() && context != null) {
                    try {
                        OAuthManager.refreshTokenIfNeeded(context)
                        val token = OAuthManager.getAccessToken(context)
                        if (!token.isNullOrBlank()) {
                            for (base in CLOUDCODE_BASES) {
                                modelsRespStr = makePostRequest("$base$MODELS_PATH", token)
                                if (!modelsRespStr.isNullOrBlank()) break
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Remote fetchAvailableModels retry failed: ${e.message}")
                    }
                }
                if (modelsRespStr.isNullOrBlank()) {
                    modelsFetchFailed = true
                    Log.w(TAG, "Local server candidate $port: model list could not be fetched (local GetAvailableModels and remote fetchAvailableModels both failed)")
                }

                val summary = parseQuotaSummaryResponse(respStr, modelsRespStr, tier, modelsFetchFailed, context)
                if (summary != null && summary.groups.isNotEmpty()) {
                    Log.i(TAG, "Successfully loaded live quota from local server on port $port (${summary.groups.size} groups, models: ${summary.availableModels.size})")
                    persistEndpoint(port, activeCsrf, context = context)
                    return summary
                }
            }
        }
        return null
    }

    private fun makePostRequest(urlStr: String, token: String): String? {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "antigravity")

        OutputStreamWriter(conn.outputStream).use { it.write("{}"); it.flush() }

        val code = conn.responseCode
        return if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
            Log.w(TAG, "Request to $urlStr failed ($code): $err")
            null
        }
    }

    fun restartLocalLanguageServer(context: Context? = null) {
        val candidates = getCandidateEndpoints(context)
        for (cand in candidates) {
            try {
                val res = queryLocalEndpointDirect(cand.port, cand.csrf, "Restart", "{}", cand.preferredScheme)
                    ?: queryLocalEndpointViaCurl(cand.port, cand.csrf, "Restart", "{}", context, cand.preferredScheme)
                if (res != null) {
                    Log.i(TAG, "Notified LanguageServer on port ${cand.port} to restart")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restart LanguageServer on port ${cand.port}: ${e.message}")
            }
        }
    }

    suspend fun fetchQuotaSummary(context: Context, preloadedTier: CodeAssistTier? = null): UserQuotaSummary? = withContext(Dispatchers.IO) {
        val tier = preloadedTier ?: fetchCodeAssistTier(context)

        val localSummary = fetchFromLocalLanguageServer(context, tier)
        if (localSummary != null && localSummary.groups.isNotEmpty()) {
            Log.i(TAG, "Priority 1: Successfully retrieved live quota from local Language Server (${localSummary.groups.size} groups)")
            return@withContext localSummary
        }

        OAuthManager.refreshTokenIfNeeded(context)
        val token = OAuthManager.getAccessToken(context)

        if (!token.isNullOrBlank()) {
            for (base in CLOUDCODE_BASES) {
                try {
                    val modelsRespStr = makePostRequest("$base$MODELS_PATH", token)
                    val quotaRespStr = makePostRequest("$base$QUOTA_PATH", token)
                    if (quotaRespStr != null) {
                        val summary = parseQuotaSummaryResponse(
                            respStr = quotaRespStr,
                            modelsRespStr = modelsRespStr,
                            tier = tier,
                            modelsFetchFailed = modelsRespStr == null,
                            context = context
                        )
                        if (summary != null && summary.groups.isNotEmpty()) {
                            Log.i(TAG, "Successfully loaded live quota from Google Cloud Code API ($base) for current token (${summary.groups.size} groups)")
                            return@withContext summary
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load live quota from Google Cloud Code API ($base): ${e.message}")
                }
            }
        }

        null
    }

    suspend fun fetchCodeAssistTier(context: Context): CodeAssistTier? = withContext(Dispatchers.IO) {
        OAuthManager.refreshTokenIfNeeded(context)
        val token = OAuthManager.getAccessToken(context) ?: return@withContext null

        for (base in CLOUDCODE_BASES) {
            try {
                val respStr = makePostRequest("$base$TIER_PATH", token) ?: continue
                val json = JSONObject(respStr)

                var curTier = TierInfo()
                if (json.has("currentTier")) {
                    val cObj = json.getJSONObject("currentTier")
                    curTier = TierInfo(
                        id = cObj.optString("id", ""),
                        name = cObj.optString("name", "Gemini Code Assist"),
                        description = cObj.optString("description", ""),
                        upgradeSubscriptionUri = cObj.optString("upgradeSubscriptionUri", ""),
                        upgradeSubscriptionText = cObj.optString("upgradeSubscriptionText", "")
                    )
                } else if (json.has("allowedTiers")) {
                    val arr = json.getJSONArray("allowedTiers")
                    if (arr.length() > 0) {
                        val cObj = arr.getJSONObject(0)
                        curTier = TierInfo(
                            id = cObj.optString("id", "standard-tier"),
                            name = cObj.optString("name", "Gemini Code Assist"),
                            description = cObj.optString("description", ""),
                            upgradeSubscriptionUri = cObj.optString("upgradeSubscriptionUri", ""),
                            upgradeSubscriptionText = cObj.optString("upgradeSubscriptionText", "")
                        )
                    }
                }

                var paidTier: TierInfo? = null
                if (json.has("paidTier")) {
                    val pObj = json.getJSONObject("paidTier")
                    paidTier = TierInfo(
                        id = pObj.optString("id", ""),
                        name = pObj.optString("name", "Google AI Pro"),
                        description = pObj.optString("description", ""),
                        upgradeSubscriptionUri = pObj.optString("upgradeSubscriptionUri", ""),
                        upgradeSubscriptionText = pObj.optString("upgradeSubscriptionText", "")
                    )
                }

                val resultTier = CodeAssistTier(currentTier = curTier, paidTier = paidTier)
                val email = OAuthManager.getUserEmail(context)
                if (!email.isNullOrBlank()) {
                    id.vern.antigravity.oauth.AccountManager.updateAccountTier(
                        context,
                        email,
                        resultTier.normalizedTierString
                    )
                }
                return@withContext resultTier
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching code assist tier from $base: ${e.message}", e)
            }
        }
        null
    }
}

