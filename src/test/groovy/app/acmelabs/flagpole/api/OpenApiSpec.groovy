package app.acmelabs.flagpole.api

import app.acmelabs.flagpole.TestFlags
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import jakarta.inject.Inject
import org.yaml.snakeyaml.Yaml
import spock.lang.Specification

@MicronautTest
class OpenApiSpec extends Specification implements TestPropertyProvider {

    @Inject
    @Client('/')
    HttpClient client

    @Override
    Map<String, String> getProperties() {
        TestFlags.properties()
    }

    Map spec() {
        new Yaml().load(client.toBlocking().retrieve('/swagger/flagpole.yml')) as Map
    }

    def "spec documents exactly the JSON API"() {
        when:
        def spec = spec()

        then:
        spec.info.title == 'flagpole'
        spec.info.version ==~ /\d+\.\d+\.\d+.*/
        spec.paths.keySet() == ['/api/flags', '/api/flags/{name}', '/api/flags/{name}/evaluate', '/api/evaluate'] as Set
    }

    def "spec documents #status for #path"() {
        expect:
        spec().paths[path].get.responses.containsKey(status)

        where:
        path                         | status
        '/api/flags'                 | '304'
        '/api/evaluate'              | '304'
        '/api/flags/{name}'          | '404'
        '/api/flags/{name}/evaluate' | '404'
    }

    def "swagger ui and its assets are served locally: #path"() {
        when:
        def response = client.toBlocking().exchange(path, String)

        then:
        response.status == HttpStatus.OK
        !response.body().contains('unpkg.com') && !response.body().contains('jsdelivr')

        where:
        path << ['/swagger-ui', '/swagger-ui/res/swagger-ui-bundle.js', '/swagger-ui/res/swagger-ui.css']
    }
}
