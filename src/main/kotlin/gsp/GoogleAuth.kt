package gsp

import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class NotSignedIn : Exception("Please sign in with Google.")

/**
 * Google sign-in for a desktop program: the browser opens, you approve, and Google sends the answer
 * to a small server that runs on this PC for a moment (127.0.0.1). The refresh token is kept in
 * %APPDATA%\GsheetPlus so you stay signed in. The functions block, so call them off the screen thread.
 */
class GoogleAuth(
    private val clientId: String = Secrets.CLIENT_ID,
    private val clientSecret: String = Secrets.CLIENT_SECRET,
    private val file: Path = Paths.get(System.getenv("APPDATA") ?: System.getProperty("user.home"), "GsheetPlus", "auth.json"),
) {
    private val http = HttpClient.newHttpClient()

    @Volatile private var accessToken: String? = null
    @Volatile private var expiresAt = 0L

    fun isConfigured(): Boolean = clientId.isNotBlank() && clientSecret.isNotBlank()

    fun isSignedIn(): Boolean = refreshToken() != null

    private fun refreshToken(): String? = runCatching {
        Json.parse(Files.readString(file)).obj()?.get("refresh_token").text().ifEmpty { null }
    }.getOrNull()

    fun signOut() {
        accessToken = null
        expiresAt = 0
        runCatching { Files.deleteIfExists(file) }
    }

    /** A valid access token, refreshed when it is about to expire. */
    @Synchronized
    fun token(): String {
        val current = accessToken
        if (current != null && System.currentTimeMillis() < expiresAt - 60_000) return current
        val refresh = refreshToken() ?: throw NotSignedIn()
        val json = postForm(
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
            "client_id" to clientId,
            "client_secret" to clientSecret,
        )
        if (json.containsKey("error")) {
            if (json["error"] == "invalid_grant") signOut()
            throw NotSignedIn()
        }
        accessToken = json["access_token"].text()
        expiresAt = System.currentTimeMillis() + ((json["expires_in"] as? Number)?.toLong() ?: 3600L) * 1000
        return accessToken!!
    }

    /** Opens the browser and waits (up to 5 minutes) for the approval. */
    fun signIn() {
        val random = SecureRandom()
        fun randomText(bytes: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))
        val verifier = randomText(48)
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomText(16)
        val answer = CompletableFuture<Map<String, String>>()

        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
                .split('&').filter { it.contains('=') }
                .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
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
                "client_id" to clientId,
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

            val result = try {
                answer.get(5, TimeUnit.MINUTES)
            } catch (e: java.util.concurrent.TimeoutException) {
                error("Sign-in timed out. Please try again.")
            }
            if (result["state"] != state) error("Sign-in answer did not match. Please try again.")
            val code = result["code"] ?: error("Sign-in was cancelled (${result["error"] ?: "no code"}).")
            val json = postForm(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirect,
                "client_id" to clientId,
                "client_secret" to clientSecret,
                "code_verifier" to verifier,
            )
            if (json.containsKey("error")) error(json["error_description"].text().ifEmpty { json["error"].text() })
            accessToken = json["access_token"].text()
            expiresAt = System.currentTimeMillis() + ((json["expires_in"] as? Number)?.toLong() ?: 3600L) * 1000
            val refresh = json["refresh_token"].text()
            if (refresh.isNotEmpty()) {
                Files.createDirectories(file.parent)
                Files.writeString(file, Json.write(mapOf("refresh_token" to refresh)))
            }
        } finally {
            server.stop(0)
        }
    }

    private fun postForm(vararg fields: Pair<String, String>): Map<String, Any?> {
        val body = fields.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val request = HttpRequest.newBuilder(URI("https://oauth2.googleapis.com/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return runCatching { Json.parse(response.body()).obj() }.getOrNull() ?: mapOf("error" to "HTTP ${response.statusCode()}")
    }

    companion object {
        const val SCOPES = "https://www.googleapis.com/auth/spreadsheets " +
            "https://www.googleapis.com/auth/drive.metadata.readonly " +
            "https://www.googleapis.com/auth/drive.file"
    }
}
