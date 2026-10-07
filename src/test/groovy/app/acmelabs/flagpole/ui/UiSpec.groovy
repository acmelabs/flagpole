package app.acmelabs.flagpole.ui

import app.acmelabs.flagpole.TestFlags
import app.acmelabs.flagpole.loader.FlagStore
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import jakarta.inject.Inject
import spock.lang.Specification

@MicronautTest
class UiSpec extends Specification implements TestPropertyProvider {

    @Inject
    @Client('/')
    HttpClient client

    @Inject
    FlagStore store

    @Override
    Map<String, String> getProperties() {
        TestFlags.properties()
    }

    def "index page lists flags, version and last reload time"() {
        when:
        def response = client.toBlocking().exchange('/', String)
        String html = response.body()

        then:
        response.status == HttpStatus.OK
        response.contentType.get().name.startsWith('text/html')
        html.contains('new-checkout')
        html.contains('New checkout flow')
        html.contains('25%')
        html.contains(FlagStore.abbreviate(store.snapshot().version()))
        html.contains('/static/htmx.min.js')
        html.contains('hx-get="/ui/evaluate"')
        !html.contains('unpkg') && !html.contains('cdn')
        html.contains('href="/swagger-ui"')
    }

    def "evaluate fragment shows every flag for the user"() {
        when:
        String html = client.toBlocking().retrieve('/ui/evaluate?userId=user_42')

        then:
        !html.contains('<html')
        html.contains('user_42')
        ['dark-mode', 'everyone', 'new-checkout'].every { html.contains(it) }
        html.contains('allow list')
        html.contains('disabled')
    }

    def "allow list can be expanded per flag"() {
        when:
        String html = client.toBlocking().retrieve('/')
        def allowCell = html.find(/(?s)<details class="allow">.*?<\/details>/)

        then: 'only new-checkout has an allow list, behind a toggle'
        html.count('<details class="allow">') == 1
        allowCell.contains('<summary title="Show allow list for new-checkout">1 user</summary>')
        allowCell.contains('<code>user_42</code>')
    }

    def "index page shows a banner while the latest reload has failed"() {
        given:
        def original = store.file().text
        store.file().text = 'flags: ['
        store.reload()

        when:
        String html = client.toBlocking().retrieve('/')

        then:
        html.contains('Latest reload failed')
        html.contains('new-checkout')

        cleanup:
        store.file().text = original
        store.reload()
    }

    def "evaluate fragment without userId is anonymous"() {
        expect:
        client.toBlocking().retrieve('/ui/evaluate').contains('anonymous')
    }

    def "static asset #path is served locally"() {
        expect:
        client.toBlocking().exchange(path, String).status == HttpStatus.OK

        where:
        path << ['/static/htmx.min.js', '/static/app.css']
    }
}
