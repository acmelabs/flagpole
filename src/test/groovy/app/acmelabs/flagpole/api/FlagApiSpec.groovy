package app.acmelabs.flagpole.api

import app.acmelabs.flagpole.TestFlags
import app.acmelabs.flagpole.eval.FlagEvaluator
import app.acmelabs.flagpole.loader.FlagStore
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import jakarta.inject.Inject
import spock.lang.Specification

@MicronautTest
class FlagApiSpec extends Specification implements TestPropertyProvider {

    @Inject
    @Client('/')
    HttpClient client

    @Inject
    FlagStore store

    @Override
    Map<String, String> getProperties() {
        TestFlags.properties()
    }

    Map getJson(String uri) {
        client.toBlocking().retrieve(uri, Map)
    }

    def "GET /api/flags returns all definitions plus version"() {
        when:
        def response = client.toBlocking().exchange('/api/flags', Map)
        def body = response.body()

        then:
        response.status == HttpStatus.OK
        body.version == store.snapshot().version()
        body.loadedAt
        body.flags*.name == ['dark-mode', 'everyone', 'new-checkout']
        body.flags.find { it.name == 'new-checkout' } == [
                name: 'new-checkout', enabled: true, rollout: 25, allow: ['user_42'], description: 'New checkout flow']
        response.header('ETag') == "\"${store.snapshot().version()}\""
    }

    def "GET /api/flags with If-None-Match: #description gives #expected"() {
        given:
        String etag = "\"${store.snapshot().version()}\""

        when:
        def response = client.toBlocking().exchange(
                HttpRequest.GET('/api/flags').header('If-None-Match', header(etag)), Map)

        then:
        response.status == expected
        response.header('ETag') == etag
        (response.status == HttpStatus.NOT_MODIFIED) == !response.body.isPresent()

        where:
        description           | header                           || expected
        'current version'     | { String e -> e }                || HttpStatus.NOT_MODIFIED
        'weak current'        | { String e -> "W/$e" }           || HttpStatus.NOT_MODIFIED
        'list containing it'  | { String e -> "\"old\", $e" }    || HttpStatus.NOT_MODIFIED
        'wildcard'            | { String e -> '*' }              || HttpStatus.NOT_MODIFIED
        'stale version'       | { String e -> '"stale"' }        || HttpStatus.OK
        'unquoted version'    | { String e -> e.replace('"', '') } || HttpStatus.OK
    }

    def "GET /api/flags/{name} returns one definition"() {
        expect:
        getJson('/api/flags/dark-mode') == [name: 'dark-mode', enabled: false, rollout: 100, allow: []]
    }

    def "GET #uri for an unknown flag is 404"() {
        when:
        client.toBlocking().exchange(uri, Map)

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND

        where:
        uri << ['/api/flags/nope', '/api/flags/nope/evaluate?userId=user_1', '/api/flags/nope/evaluate']
    }

    def "GET /api/flags/#flag/evaluate?userId=#userId is #expected"() {
        given:
        String uri = "/api/flags/$flag/evaluate" + (userId == null ? '' : "?userId=$userId")

        expect:
        getJson(uri) == [flag: flag, enabled: expected]

        where:
        flag           | userId          || expected
        'dark-mode'    | 'user_42'       || false
        'everyone'     | null            || true
        'new-checkout' | 'user_42'       || true
        'new-checkout' | null            || false
        'new-checkout' | inBucket(true)  || true
        'new-checkout' | inBucket(false) || false
    }

    def "GET /api/evaluate returns every flag for the user"() {
        when:
        def body = getJson('/api/evaluate?userId=user_42')

        then:
        body == [version: store.snapshot().version(), userId: 'user_42',
                 flags  : ['dark-mode': false, 'everyone': true, 'new-checkout': true]]
    }

    def "GET /api/evaluate without userId evaluates anonymously"() {
        expect:
        getJson('/api/evaluate').flags == ['dark-mode': false, 'everyone': true, 'new-checkout': false]
    }

    def "GET /api/evaluate with #description userId is anonymous"() {
        when:
        def response = client.toBlocking().exchange(uri, Map)

        then:
        response.body() == [version: store.snapshot().version(),
                            flags  : ['dark-mode': false, 'everyone': true, 'new-checkout': false]]
        response.header('ETag') == "\"${store.snapshot().version()}\""

        where:
        description | uri
        'no'        | '/api/evaluate'
        'an empty'  | '/api/evaluate?userId='
        'a blank'   | '/api/evaluate?userId=%20%20'
    }

    def "GET /api/evaluate ETag is per user, so one user's ETag never gives another a 304"() {
        given:
        String u1Etag = client.toBlocking().exchange('/api/evaluate?userId=u1', Map).header('ETag')
        String u2Etag = client.toBlocking().exchange('/api/evaluate?userId=u2', Map).header('ETag')

        when:
        def response = client.toBlocking().exchange(
                HttpRequest.GET('/api/evaluate?userId=u2').header('If-None-Match', u1Etag), Map)

        then:
        u1Etag ==~ /"${store.snapshot().version()}-[0-9a-f]{16}"/
        u1Etag != u2Etag
        response.status == HttpStatus.OK
        response.body().userId == 'u2'
    }

    def "GET /api/evaluate supports If-None-Match"() {
        given:
        def first = client.toBlocking().exchange('/api/evaluate?userId=u1', Map)

        when:
        def second = client.toBlocking().exchange(
                HttpRequest.GET('/api/evaluate?userId=u1').header('If-None-Match', first.header('ETag')), Map)

        then:
        second.status == HttpStatus.NOT_MODIFIED
    }

    def "CORS is off unless origins are configured"() {
        expect:
        CorsSpec.allowOrigin(client, 'https://app.example.com') == null
    }

    /** Finds a user whose bucket for new-checkout (rollout 25) is inside or outside the rollout. */
    static String inBucket(boolean inside) {
        (0..<1000).collect { "user_$it" as String }
                .find { (FlagEvaluator.bucket('new-checkout', it) < 25) == inside }
    }
}
