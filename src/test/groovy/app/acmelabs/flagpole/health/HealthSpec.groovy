package app.acmelabs.flagpole.health

import app.acmelabs.flagpole.TestFlags
import app.acmelabs.flagpole.loader.FlagStore
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import jakarta.inject.Inject
import spock.lang.Specification

@MicronautTest
class HealthSpec extends Specification implements TestPropertyProvider {

    @Inject
    @Client('/')
    HttpClient client

    @Inject
    FlagStore store

    @Override
    Map<String, String> getProperties() {
        TestFlags.properties()
    }

    def "health includes config version and last reload time"() {
        when:
        Map body = client.toBlocking().retrieve('/health', Map)
        def snapshot = store.snapshot()
        def lastCheck = store.lastSuccessfulCheckAt().toString()

        then:
        body.status == 'UP'
        with(body.details.flags.details) {
            version == snapshot.version()
            lastReloadAt == snapshot.loadedAt().toString()
            lastSuccessfulCheckAt == lastCheck
            flagCount == 3
            !containsKey('lastReloadError')
        }
    }

    def "Kubernetes probe endpoint #path is UP"() {
        expect:
        client.toBlocking().retrieve(path, Map).status == 'UP'

        where:
        path << ['/health/liveness', '/health/readiness']
    }

    def "health stays UP and reports the error after a failed reload"() {
        given:
        def original = store.file().text
        store.file().text = 'flags: ['

        when:
        store.reload()
        Map body = client.toBlocking().retrieve('/health', Map)

        then:
        body.status == 'UP'
        body.details.flags.details.lastReloadError.contains('malformed YAML')
        body.details.flags.details.version == store.snapshot().version()

        cleanup:
        store.file().text = original
        store.reload()
    }
}
