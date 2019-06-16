package net.mbonnin.arcanetracker.hsreplay

import com.squareup.moshi.Moshi
import io.fabric.sdk.android.services.network.HttpRequest.HEADER_AUTHORIZATION
import io.ktor.client.response.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.hearthsim.hsreplay.HsReplayOauthApi
import net.hearthsim.hsreplay.model.Token
import net.mbonnin.arcanetracker.Settings
import net.mbonnin.arcanetracker.Utils
import okhttp3.*
import java.io.IOException

class HsReplayInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        synchronized(lock) {
            var request = chain.request()

            val requestBuilder = request.newBuilder()

            requestBuilder.header(HEADER_AUTHORIZATION, "Bearer ${accessToken}")
            request = requestBuilder.build()

            var response = chain.proceed(request)
            if (!response.isSuccessful && accessToken != null && refreshToken != null) {
                val result = tryRefreshToken()
                when (result) {
                    is RefreshResult.RecoverableError -> {
                        throw IOException("no token, retry later")
                    }
                    is RefreshResult.LoggedOut -> {
                        //logoutInternal()
                        throw IOException("logged out")
                    }
                }
                requestBuilder.header(HEADER_AUTHORIZATION, "Bearer ${accessToken}")
                response = chain.proceed(requestBuilder.build())
            }

            return response
        }
    }


    sealed class RefreshResult {
        object Success : RefreshResult()
        object LoggedOut : RefreshResult()
        class RecoverableError(val e: Exception) : RefreshResult()
    }

    companion object {
        const val A = "pk_live_iKPWQuznmNf2BbBCxZa1VzmP"
        const val B = "sk_live_20180319oDB6PgKuHSwnDVs5B5SLBmh3"
        const val AUTHORIZE_URL = "https://hsreplay.net/oauth2/authorize/?utm_source=arcanetracker&utm_medium=client"
        const val CALLBACK_URL = "arcanetracker://callback/"

        private var accessToken: String? = Settings.getString(Settings.HSREPLAY_OAUTH_ACCESS_TOKEN, null)
        var refreshToken: String? = Settings.getString(Settings.HSREPLAY_OAUTH_REFRESH_TOKEN, null)

        private val lock = Object()

        private fun storeToken(response: Response): Result<Unit> {
            val tokenResponse = response.body()?.string()
            if (tokenResponse == null) {
                val e = Exception("Body Error")
                return Result.failure(e)
            }

            try {
                val map = Moshi.Builder().build().adapter<Map<String, String>>(Map::class.java).fromJson(tokenResponse)!!
                accessToken = map.get("access_token")
                refreshToken = map.get("refresh_token")
            } catch (e: Exception) {
                return Result.failure(e)
            }

            Settings.set(Settings.HSREPLAY_OAUTH_ACCESS_TOKEN, accessToken!!)
            Settings.set(Settings.HSREPLAY_OAUTH_REFRESH_TOKEN, refreshToken!!)

            return Result.success(Unit)
        }

        /**
         * Configures the interceptor with a code got from a Oauth client flow. This will
         * exchange the code and remember it for future usage.
         * This will block, do not call from main thread
         */
        suspend fun login(code: String): Result<Unit> = withContext(Dispatchers.IO) {
            val token = try {
                HsReplayOauthApi().login(code)
            } catch (e: Exception) {
                e
            }

            if (token !is Token) {
                return@withContext Result.failure<Unit>(token as java.lang.Exception)
            }

            synchronized(lock) {
                accessToken = token.access_token
                refreshToken = token.refresh_token
                Settings.set(Settings.HSREPLAY_OAUTH_ACCESS_TOKEN, token.access_token)
                Settings.set(Settings.HSREPLAY_OAUTH_REFRESH_TOKEN, token.refresh_token)
                lock.notifyAll()
            }

            return@withContext Result.success(Unit)
        }

        fun logout() {
            synchronized(lock) {
                logoutInternal()
            }
        }

        fun logoutInternal() {
            accessToken = null
            refreshToken = null

            Settings.remove(Settings.HSREPLAY_OAUTH_ACCESS_TOKEN)
            Settings.remove(Settings.HSREPLAY_OAUTH_REFRESH_TOKEN)
        }

        fun refreshToken(): RefreshResult {
            val response = runBlocking {
                try {
                    HsReplayOauthApi().refresh(refreshToken!!)
                } catch (e: Exception) {
                    e
                }
            }

            if (response !is HttpResponse) {
                return RefreshResult.RecoverableError(response as Exception)
            }

            when (response.status.value / 100) {
                4 -> return RefreshResult.LoggedOut
                else -> return RefreshResult.RecoverableError(IOException("HTTP token refresh error ${response.status.value}"))
            }

            if (storeToken(response).isFailure) {
                return RefreshResult.RecoverableError(IOException("Cannot store token"))
            }

            return RefreshResult.Success
        }

        fun tryRefreshToken(): RefreshResult {
            val delays = listOf(0, 0, 30, 60, 120, 240, 480)

            delays.forEach {
                try {
                    Thread.sleep(it * 1000.toLong())
                } catch (e: Exception) {
                    // ignored
                }
                val result = refreshToken()
                when (result) {
                    is RefreshResult.Success -> return result
                    is RefreshResult.LoggedOut -> {
                        Utils.reportNonFatal(Exception("User Logged out"))
                        return result
                    }
                    is RefreshResult.RecoverableError -> {
                        Utils.reportNonFatal(result.e)
                        // retry
                    }
                }
            }

            return RefreshResult.RecoverableError(Exception("cannot refresh token, maybe the user is offline or servers are down"))

        }
    }
}
