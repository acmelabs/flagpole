package app.acmelabs.flagpole.api

import app.acmelabs.flagpole.TestFlags
import io.micronaut.http.HttpMethod
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import jakarta.inject.Inject
import spock.lang.Specification

@MicronautTest
class CorsSpec extends Specification implements TestPropertyProvider {

    static final String ALLOWED = 'https://app.example.com'

    @Inject
    @Client('/')
    HttpClient client

    @Override
    Map<String, String> getProperties() {
        TestFlags.properties() + ['micronaut.server.cors.configurations.api.allowed-origins': "$ALLOWED,https://other.example.com"]
    }

    def "a listed origin gets CORS headers, with the ETag exposed"() {
        when:
        def response = client.toBlocking().exchange(HttpRequest.GET('/api/evaluate?userId=u1').header('Origin', ALLOWED), Map)

        then:
        response.header('Access-Control-Allow-Origin') == ALLOWED
        response.header('Access-Control-Expose-Headers') == 'ETag'
        response.header('Access-Control-Allow-Credentials') == null
    }

    def "an unlisted origin gets no CORS headers"() {
        expect:
        allowOrigin(client, 'https://evil.example.com') == null
    }

    /**
     * Access-Control-Allow-Origin for a GET from the given origin. On a localhost server Micronaut also answers
     * 403 to unlisted origins (drive-by-localhost protection), so this reads the header from either outcome.
     */
    static String allowOrigin(HttpClient client, String origin) {
        try {
            client.toBlocking().exchange(HttpRequest.GET('/api/flags').header('Origin', origin), Map)
                    .header('Access-Control-Allow-Origin')
        } catch (HttpClientResponseException e) {
            e.response.header('Access-Control-Allow-Origin')
        }
    }

    def "preflight allows GET with If-None-Match"() {
        when:
        def response = client.toBlocking().exchange(HttpRequest.OPTIONS('/api/flags')
                .header('Origin', ALLOWED)
                .header('Access-Control-Request-Method', 'GET')
                .header('Access-Control-Request-Headers', 'If-None-Match'))

        then:
        response.header('Access-Control-Allow-Origin') == ALLOWED
        response.header('Access-Control-Allow-Methods') == 'GET'
    }
}
