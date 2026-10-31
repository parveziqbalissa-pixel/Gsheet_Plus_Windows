package gsp

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.awt.Desktop
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class NotSignedIn : Exception("Please sign in with Google.")

/**
 * Google sign-in for a desktop program: the browser opens, you approve, and Google sends the answer
 * to a small server that runs on this PC for a moment (127.0.0.1). The refresh token is kept in
 * %APPDATA%\GsheetPlus so you stay signed in.
 */
class GoogleAuth {
    private val http = HttpClient.newHttpClient()
    private val file: Path = Paths.get(System.getenv("APPDATA") ?: System.getProperty("user.home"), "GsheetPlus", "auth.json")

    @Volatile private var accessToken: String? = null
    @Volatile private var expiresAt = 0L

    fun isConfigured(): Boolean = Secrets.CLIENT_ID.isNotBlank() && Secrets.CLIENT_SECRET.isNotBlank()

    fun isSignedIn(): Boolean = refreshToken() != null

    private fun refreshToken(): String? = runCatching {
        JSONObject(Files.readString(file)).optString("refresh_token").ifEmpty { null }
    }.getOrNull()

    fun signOut() {
        accessToken = null
        expiresAt = 0
        runCatching { Files.deleteIfExists(file) }
    }

    /** A valid access token, refreshed when it is about to expire. */
    suspend fun token(): String {
        val current = accessToken
        if (current != null && System.currentTimeMillis() < expiresAt - 60_000) return current
        val refresh = refreshToken() ?: throw NotSignedIn()
        val json = postForm(
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
            "client_id" to Secrets.CLIENT_ID,
            "client_secret" to Secrets.CLIENT_SECRET,
        )
        if (json.has("error")) {
            if (json.optString("error") == "invalid_grant") signOut()
            throw NotSignedIn()
        }
        accessToken = json.getString("access_token")
        expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
        return accessToken!!
    }

    /** Opens the browser and waits (up to 5 minutes) for the approval. */
    suspend fun signIn() = withContext(Dispatchers.IO) {
        val random = SecureRandom()
        fun randomText(bytes: Int) = ByteArray(bytes).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val verifier = randomText(48)
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomText(16)
        val answer = CompletableDeferred<Map<String, String>>()

        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
                .split('&').filter { it.contains('=') }
                .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val page = "<html><body style='font-family:sans-serif;padding:40px'><h2>Gsheet_Plus</h2>" +
                (if (query.containsKey("code")) "<p>Signed in. You can close this tab and return to the app.</p>" else "<p>Sign-in was not completed.</p>") +
                "</body></html>"
            val bytes = page.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            answer.complete(query)
        }
        server.start()
        try {
            val redirect = "http://127.0.0.1:${server.address.port}"
            val url = "https://accounts.google.com/o/oauth2/v2/auth?" + listOf(
                "client_id" to Secrets.CLIENT_ID,
                "redirect_uri" to redirect,
                "response_type" to "code",
                "scope" to SCOPES,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "access_type" to "offline",
                "prompt" to "consent",
                "state" to state,
            ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
            Desktop.getDesktop().browse(URI(url))

            val result = withTimeout(5 * 60_000L) { answer.await() }
            if (result["state"] != state) error("Sign-in answer did not match. Please try again.")
            val code = result["code"] ?: error("Sign-in was cancelled (${result["error"] ?: "no code"}).")
            val json = postForm(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirect,
                "client_id" to Secrets.CLIENT_ID,
                "client_secret" to Secrets.CLIENT_SECRET,
                "code_verifier" to verifier,
            )
            if (json.has("error")) error(json.optString("error_description", json.getString("error")))
            accessToken = json.getString("access_token")
            expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
            val refresh = json.optString("refresh_token")
            if (refresh.isNotEmpty()) {
                Files.createDirectories(file.parent)
                Files.writeString(file, JSONObject().put("refresh_token", refresh).toString())
            }
        } finally {
            server.stop(0)
        }
    }

    private suspend fun postForm(vararg fields: Pair<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val body = fields.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val request = HttpRequest.newBuilder(URI("https://oauth2.googleapis.com/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        runCatching { JSONObject(response.body()) }.getOrElse { JSONObject().put("error", "HTTP ${response.statusCode()}") }
    }

    companion object {
        const val SCOPES = "https://www.googleapis.com/auth/spreadsheets " +
            "https://www.googleapis.com/auth/drive.metadata.readonly"
    }
}
