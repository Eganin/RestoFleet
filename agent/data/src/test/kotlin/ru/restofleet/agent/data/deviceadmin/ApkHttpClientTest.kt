package ru.restofleet.agent.data.deviceadmin

import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows
import java.io.IOException

class ApkHttpClientTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    @Test
    fun timeoutsAndRedirectPolicyAreStrict() {
        val client = apkHttpClient()
        assertThat(client.connectTimeoutMillis).isEqualTo(15_000)
        assertThat(client.readTimeoutMillis).isEqualTo(60_000)
        assertThat(client.callTimeoutMillis).isEqualTo(600_000)
        assertThat(client.followRedirects).isTrue()
        assertThat(client.followSslRedirects).isFalse()
        assertThat(client.retryOnConnectionFailure).isFalse()
    }

    @Test
    fun trustIsTheSystemDefault() {
        val client = apkHttpClient()
        val stock = OkHttpClient()
        assertThat(client.connectionSpecs).containsExactly(ConnectionSpec.MODERN_TLS)
        assertThat(client.certificatePinner.pins).isEmpty()
        assertThat(client.hostnameVerifier.javaClass).isEqualTo(stock.hostnameVerifier.javaClass)
        assertThat(client.sslSocketFactory.javaClass).isEqualTo(stock.sslSocketFactory.javaClass)
    }

    @Test
    fun cleartextHttpIsRefusedAtClientLevel() {
        server.enqueue(MockResponse.Builder().code(200).build())
        assertThrows(IOException::class.java) {
            apkHttpClient().newCall(Request.Builder().url(server.url("/a.apk")).build()).execute()
        }
        assertThat(server.requestCount).isEqualTo(0)
    }
}
