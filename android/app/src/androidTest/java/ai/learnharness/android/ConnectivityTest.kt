package ai.learnharness.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Public endpoint reachability only. Never reads or sends an account credential. */
@RunWith(AndroidJUnit4::class)
class ConnectivityTest {
    @Test fun publicEndpointDiagnostics() {
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue("Run explicitly with -e publicConnectivity true",arguments.getString("publicConnectivity")=="true")
        val client=OkHttpClient.Builder().callTimeout(25,TimeUnit.SECONDS).connectTimeout(15,TimeUnit.SECONDS).followRedirects(false).build()
        val endpoints=mutableListOf("https://auth.openai.com/.well-known/jwks.json" to 200,"https://api.openai.com/v1/models" to 401)
        arguments.getString("syncUrl")?.let {require(it.startsWith("https://"));endpoints.add(it to 200)}
        for((url,expected) in endpoints) {
            client.newCall(Request.Builder().url(url).build()).execute().use {
                android.util.Log.i("HarnessNetworkTest",url+" HTTP "+it.code)
                assertEquals("Unexpected response from $url",expected,it.code)
            }
        }
    }
}
