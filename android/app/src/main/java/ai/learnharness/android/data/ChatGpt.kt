package ai.learnharness.android.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import ai.learnharness.android.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val ISSUER = "https://auth.openai.com"
private const val RESOURCE = "https://api.openai.com/v1"
private val SCOPES = setOf("openid", "profile", "email", "offline_access", "resource.invoke", "chatgpt.tokens.use.direct")
private fun randomToken() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
private fun JsonObject.str(name: String) = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()

@Serializable data class ChatGptModel(val slug: String, val display_name: String = "", val visibility: String = "")
@Serializable data class ChatGptAccount(
    val hostId: String = "urn:uuid:${newId()}", val clientId: String = "", val subject: String = "",
    val email: String = "", val idToken: String = "", val accessToken: String = "", val refreshToken: String = "",
    val expiresAt: Long = 0, val scopes: Set<String> = emptySet(), val selectedModel: String = ""
)

/** A distinct public OAuth client on this phone. Credentials never enter learning files or sync. */
class ChatGpt(private val context: Context) {
    private val vault = SecretStore(context, "chatgpt-v1")
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS).callTimeout(300, TimeUnit.SECONDS).build()
    private val accountHttp = http.newBuilder().readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build()
    private var listener: ServerSocket? = null
    fun account(): ChatGptAccount = if (vault.hasKey()) AppJson.decodeFromString(vault.readKey()) else ChatGptAccount()
    private fun save(account: ChatGptAccount) = vault.save(ProviderSettings(RESOURCE, "chatgpt"), AppJson.encodeToString(account))
    fun connected(): Boolean = account().let { it.accessToken.isNotBlank() && it.scopes.containsAll(SCOPES) }
    fun cancelSignIn() { listener?.close() }
    suspend fun selectModel(slug: String) = mutex.withLock {
        require(modelsUnlocked().any { it.slug == slug }) { "Choose a model available to this ChatGPT account" }
        save(account().copy(selectedModel=slug))
    }
    suspend fun signOut() = mutex.withLock {
        val old = account()
        // Keep the installation identity, but remove all account credentials and hints.
        save(ChatGptAccount(hostId=old.hostId))
    }
    private suspend fun request(request: Request, streaming: Boolean = false): JsonObject = suspendCancellableCoroutine { continuation ->
        val call = (if(streaming)http else accountHttp).newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val detail=when(e) {
                    is java.net.UnknownHostException -> "DNS could not resolve ${request.url.host}. Check the phone's VPN or Private DNS settings."
                    is javax.net.ssl.SSLException -> "The secure connection to ${request.url.host} failed. Check the phone's date and network."
                    is java.net.SocketTimeoutException -> "The connection to ${request.url.host} timed out. Try another network."
                    else -> "Could not connect to ${request.url.host} (${e.javaClass.simpleName}). Try again after checking the network."
                }
                if(continuation.isActive) continuation.resumeWithException(IOException(detail,e))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val source = it.body?.source() ?: throw IOException("ChatGPT returned an empty response")
                        if (!it.isSuccessful) {
                            source.request(32768)
                            val raw = source.readUtf8(minOf(source.buffer.size,32768))
                            val code = runCatching { AppJson.parseToJsonElement(raw).jsonObject.let { obj -> (obj["error"] as? JsonObject)?.str("code") ?: obj.str("error") } }.getOrDefault("")
                            throw OAuthFailure(it.code, code, when(it.code) {
                                401 -> "ChatGPT sign-in expired. Reconnect in Settings."
                                403 -> "This ChatGPT account has not authorized plan use for LearnHarness. Reconnect and review permissions."
                                429 -> "Your ChatGPT usage limit was reached. Check ChatGPT Settings → Usage before retrying."
                                else -> "ChatGPT request failed (${it.code}). Try again later."
                            })
                        }
                        if (!streaming) {
                            source.request(2_000_001)
                            check(source.buffer.size <= 2_000_000) { "ChatGPT response exceeded the size limit" }
                            AppJson.parseToJsonElement(source.readUtf8()).jsonObject
                        } else {
                            var completed: JsonObject? = null
                            var bytes = 0
                            val data = StringBuilder()
                            fun event() {
                                if(data.isEmpty()) return
                                val raw = data.toString().trim(); data.clear()
                                if(raw == "[DONE]") return
                                val e = AppJson.parseToJsonElement(raw).jsonObject
                                when(e.str("type")) {
                                    "response.completed" -> {
                                        val r = e["response"]!!.jsonObject
                                        check(r.str("status") == "completed" && (r["error"] == null || r["error"] == JsonNull)) { "ChatGPT did not complete this response" }
                                        completed = r
                                    }
                                    "response.failed", "response.incomplete", "error" -> throw IOException("ChatGPT could not finish this response. Check your usage allowance and retry explicitly.")
                                }
                            }
                            while(!source.exhausted()) {
                                val line = source.readUtf8LineStrict(2_000_000)
                                bytes += line.length
                                check(bytes <= 12_000_000) { "ChatGPT response exceeded the size limit" }
                                if(line.isBlank()) event() else if(line.startsWith("data:")) data.append(line.removePrefix("data:").trimStart()).append('\n')
                            }
                            event()
                            completed ?: throw IOException("The ChatGPT stream was interrupted. Your input is saved; retry when ready.")
                        }
                    }
                    if(continuation.isActive) continuation.resume(result)
                } catch(e:Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    private suspend fun grant(fields: Map<String,String>): JsonObject {
        val form = FormBody.Builder().add("resource", RESOURCE)
        fields.forEach { (key,value) -> form.add(key,value) }
        return request(Request.Builder().url("$ISSUER/api/accounts/oauth/token").post(form.build()).build())
    }
    private suspend fun verifiedIdentity(token: String, clientId: String, nonce: String? = null): JsonObject {
        val parts = token.split('.')
        require(parts.size == 3) { "Invalid ChatGPT identity token" }
        val decoder = Base64.getUrlDecoder()
        val header = AppJson.parseToJsonElement(String(decoder.decode(parts[0]))).jsonObject
        require(header.str("alg") == "RS256" && header.str("kid").isNotBlank()) { "Unsupported ChatGPT identity signature" }
        val keys = request(Request.Builder().url("$ISSUER/.well-known/jwks.json").build())["keys"]!!.jsonArray
        val key = keys.map { it.jsonObject }.single { it.str("kid") == header.str("kid") && it.str("kty") == "RSA" }
        require(key.str("use") in setOf("", "sig") && key.str("alg") in setOf("", "RS256"))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1,decoder.decode(key.str("n"))), BigInteger(1,decoder.decode(key.str("e")))))
        val verifier = Signature.getInstance("SHA256withRSA").apply { initVerify(publicKey); update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)) }
        require(verifier.verify(decoder.decode(parts[2]))) { "ChatGPT identity signature could not be verified" }
        val claims = AppJson.parseToJsonElement(String(decoder.decode(parts[1]))).jsonObject
        val audience = when(val aud = claims["aud"]) { is JsonArray -> aud.map { it.jsonPrimitive.content }; is JsonPrimitive -> listOf(aud.content); else -> emptyList() }
        val now = System.currentTimeMillis()/1000
        require(claims.str("iss") == ISSUER && clientId in audience && claims.str("sub").isNotBlank()) { "ChatGPT returned a different identity" }
        require((claims["exp"]?.jsonPrimitive?.longOrNull ?: 0) > now && (claims["nbf"]?.jsonPrimitive?.longOrNull ?: 0) <= now+30) { "ChatGPT identity token expired" }
        require(audience.size <= 1 || claims.str("azp") == clientId) { "ChatGPT identity audience mismatch" }
        if(nonce != null) require(claims.str("nonce") == nonce) { "ChatGPT sign-in nonce mismatch" }
        return claims
    }
    private fun tokens(body: JsonObject, old: ChatGptAccount): ChatGptAccount {
        val scopes = body.str("scope").takeIf { it.isNotBlank() }?.split(Regex("\\s+"))?.toSet() ?: old.scopes
        require(scopes.containsAll(SCOPES)) { "ChatGPT plan permission is missing. Reconnect and authorize plan use." }
        val expires = body["expires_in"]?.jsonPrimitive?.longOrNull ?: 0
        require(body.str("token_type").equals("bearer",true) && expires > 0 && body.str("access_token").isNotBlank() && !body.str("access_token").startsWith("sk-")) { "Invalid ChatGPT credentials" }
        return old.copy(accessToken=body.str("access_token"), refreshToken=body.str("refresh_token").ifBlank { old.refreshToken },
            idToken=body.str("id_token").ifBlank { old.idToken }, expiresAt=System.currentTimeMillis()+expires*1000, scopes=scopes)
    }
    suspend fun signIn(onProgress:(String)->Unit = {},awaitForeground:suspend ()->Unit): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val old = account(); save(old) // Persist this phone's identity before registration.
            val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")); listener = server
            val state = randomToken(); val nonce = randomToken(); val verifier = randomToken()
            val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
            val url = Uri.parse("$ISSUER/api/accounts/authorize").buildUpon()
                .appendQueryParameter("client_id",old.clientId.ifBlank { "dynamic_agent_client" })
                .appendQueryParameter("ext_agent_host_id",old.hostId).appendQueryParameter("response_type","code")
                .appendQueryParameter("redirect_uri",redirect).appendQueryParameter("scope",SCOPES.joinToString(" "))
                .appendQueryParameter("resource",RESOURCE).appendQueryParameter("state",state).appendQueryParameter("nonce",nonce)
                .appendQueryParameter("code_challenge_method","S256").appendQueryParameter("code_challenge",challenge)
            if(old.clientId.isBlank()) url.appendQueryParameter("agent_name_hint","LearnHarness Android")
            else if(old.idToken.isNotBlank()) url.appendQueryParameter("id_token_hint",old.idToken)
            try {
                val powerSaving=context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode==true
                onProgress("Waiting for sign-in in your browser. Complete approval there, then return here. You can cancel below."+
                    if(powerSaving)" Power saving is on; turn it off temporarily if approval gets stuck." else "")
                withContext(Dispatchers.Main) { context.startActivity(Intent(Intent.ACTION_VIEW,url.build()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                val callback = runInterruptible {
                    var result: Map<String,String>? = null
                    val deadline = System.currentTimeMillis()+300000
                    while(result == null && System.currentTimeMillis() < deadline) {
                        server.soTimeout = (deadline-System.currentTimeMillis()).coerceAtLeast(1).toInt()
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            val input = socket.getInputStream().bufferedReader()
                            val line = buildString {
                                while(length<16384) {
                                    val c=input.read();if(c<0||c==10)break
                                    if(c!=13)append(c.toChar())
                                }
                            }
                            require(line.length < 16384) { "Invalid sign-in callback" }
                            val target = line.split(' ').getOrNull(1).orEmpty()
                            val uri = Uri.parse(target)
                            val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() }
                            val valid = line.startsWith("GET ") && uri.path == "/auth/callback" && MessageDigest.isEqual(params["state"].orEmpty().toByteArray(),state.toByteArray())
                            val message = if(valid) "Sign-in received. Return to LearnHarness to finish connecting." else "Invalid sign-in callback. Return to LearnHarness."
                            val body = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>LearnHarness</title><p>$message</p>"
                            socket.getOutputStream().write(("HTTP/1.1 ${if(valid) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'none'\r\nConnection: close\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray())
                            if(valid) result = params
                        }
                    }
                    result ?: throw IOException("The browser did not finish ChatGPT sign-in. Close its old tab and start Continue with ChatGPT again.")
                }
                onProgress("Sign-in received. Return to LearnHarness to finish connecting.")
                require(callback["error"].isNullOrBlank()) { "ChatGPT sign-in was declined. Your previous account was kept." }
                val client = callback["client_id"].orEmpty().ifBlank { old.clientId }
                require(client.isNotBlank() && client != "dynamic_agent_client" && (old.clientId.isBlank() || old.clientId == client)) { "ChatGPT registration did not match" }
                // Retain the issued registration even if a one-use code expires.
                save(old.copy(clientId=client))
                // Chrome owns the foreground during the loopback callback. Android may block
                // this app's background DNS/data, so exchange the one-use code only on return.
                withTimeout(180000){awaitForeground()}
                onProgress("Connecting to OpenAI…")
                val body = grant(mapOf("grant_type" to "authorization_code", "client_id" to client,"code" to requireNotNull(callback["code"]),"code_verifier" to verifier,"redirect_uri" to redirect))
                onProgress("Verifying your ChatGPT account…")
                val identity = verifiedIdentity(body.str("id_token"),client,nonce)
                require(old.subject.isBlank() || old.subject == identity.str("sub")) { "ChatGPT returned a different account. Sign out first to change accounts." }
                val next = tokens(body,old.copy(clientId=client,subject=identity.str("sub"),email=identity.str("email")))
                save(next)
                next.email.ifBlank { "ChatGPT" }
            } catch(e:TimeoutCancellationException) {
                throw IOException("Return to LearnHarness after browser approval to finish signing in. This attempt expired; please try again.",e)
            } catch(e:java.net.SocketTimeoutException) {
                throw IOException("The browser did not finish ChatGPT sign-in. Close its old tab and start Continue with ChatGPT again.",e)
            } finally { server.close(); listener = null }
        }
    }
    private suspend fun tokenUnlocked(): String {
        val old = account()
        require(old.accessToken.isNotBlank() && old.scopes.containsAll(SCOPES)) { "Continue with ChatGPT in Settings to start learning" }
        if(old.expiresAt > System.currentTimeMillis()+60000) return old.accessToken
        require(old.refreshToken.isNotBlank()) { "Reconnect ChatGPT in Settings" }
        try {
            val body = grant(mapOf("grant_type" to "refresh_token","client_id" to old.clientId,"refresh_token" to old.refreshToken))
            if(body.str("id_token").isNotBlank()) require(verifiedIdentity(body.str("id_token"),old.clientId).str("sub") == old.subject) { "ChatGPT refreshed a different account" }
            val next = tokens(body,old); save(next); return next.accessToken
        } catch(e: OAuthFailure) {
            if(e.oauthCode == "invalid_grant") save(old.copy(accessToken="",refreshToken="",expiresAt=0))
            throw e
        }
    }
    private suspend fun modelsUnlocked(): List<ChatGptModel> {
        val result = request(Request.Builder().url("$RESOURCE/models").header("Authorization","Bearer ${tokenUnlocked()}").build())
        return result["models"]?.jsonArray.orEmpty().map { AppJson.decodeFromJsonElement<ChatGptModel>(it) }.filter { it.visibility == "list" }
    }
    suspend fun models() = mutex.withLock { modelsUnlocked() }
    suspend fun infer(payload: JsonObject): JsonObject {
        val (token, model) = mutex.withLock { tokenUnlocked() to account().selectedModel }
        require(model.isNotBlank()) { "Choose your ChatGPT model in Settings" }
        val body = JsonObject(payload + mapOf("model" to JsonPrimitive(model), "store" to JsonPrimitive(false), "stream" to JsonPrimitive(true)))
        return request(Request.Builder().url("$RESOURCE/responses").header("Authorization","Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build(),streaming=true)
    }
}

private class OAuthFailure(val status: Int, val oauthCode: String, message: String): IOException(message)
