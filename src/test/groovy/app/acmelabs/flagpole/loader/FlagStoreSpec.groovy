package app.acmelabs.flagpole.loader

import app.acmelabs.flagpole.config.FlagsConfig
import app.acmelabs.flagpole.util.Sha256
import io.micronaut.context.exceptions.ConfigurationException
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

import static app.acmelabs.flagpole.loader.FlagStore.ReloadResult.*

class FlagStoreSpec extends Specification {

    static final String VALID = '''\
        flags:
          new-checkout:
            enabled: true
            rollout: 25
          dark-mode:
            enabled: false
        '''.stripIndent()

    @TempDir
    Path dir

    Path file

    def setup() {
        file = dir.resolve('flags.yaml')
    }

    FlagStore store(String path = null) {
        path = path == null ? file.toString() : path
        new FlagStore(new FlagsConfig() {
            String getFile() { path }
            Duration getReloadInterval() { Duration.ofSeconds(5) }
        }, new FlagFileParser())
    }

    def "valid file loads at startup"() {
        given:
        file.text = VALID

        when:
        def store = store()

        then:
        store.snapshot().flags().keySet() == ['dark-mode', 'new-checkout'] as Set
        store.snapshot().version() == Sha256.hex(VALID.bytes)
        store.lastError() == null
    }

    def "startup fails when #problem"() {
        given:
        if (content != null) file.text = content

        when:
        store(path)

        then:
        def e = thrown(ConfigurationException)
        e.message.contains(message)

        where:
        problem              | content                         | path || message
        'file is missing'    | null                            | null || 'Flags file not found'
        'file is invalid'    | 'flags:\n  Bad:\n    enabled: 1' | null || "flag name 'Bad'"
        'FLAGS_FILE not set' | null                            | ''   || 'FLAGS_FILE'
    }

    def "unchanged file is not reloaded"() {
        given:
        file.text = VALID
        def store = store()
        def before = store.snapshot()

        when:
        file.toFile().setLastModified(System.currentTimeMillis() + 60_000)

        then:
        store.reload() == UNCHANGED
        store.snapshot().is(before)
    }

    def "changed file is picked up"() {
        given:
        file.text = VALID
        def store = store()
        def before = store.snapshot()

        when:
        file.text = VALID.replace('enabled: false', 'enabled: true')

        then:
        store.reload() == RELOADED
        store.snapshot().version() != before.version()
        store.snapshot().flags()['dark-mode'].enabled()
        store.snapshot().loadedAt() >= before.loadedAt()
    }

    def "invalid file on reload keeps the last good config: #problem"() {
        given:
        file.text = VALID
        def store = store()
        def good = store.snapshot()

        when:
        change(file)

        then:
        store.reload() == FAILED
        store.snapshot().is(good)
        store.lastError().message().contains(message)

        when: 'the file is fixed again'
        file.text = VALID.replace('rollout: 25', 'rollout: 30')

        then:
        store.reload() == RELOADED
        store.lastError() == null
        store.snapshot().flags()['new-checkout'].rollout() == 30

        where:
        problem           | change                                     || message
        'invalid content' | { Path f -> f.text = 'flags:\n  x: {}' }   || "'enabled' is required"
        'malformed YAML'  | { Path f -> f.text = 'flags: [' }          || 'malformed YAML'
        'empty file'      | { Path f -> f.text = '' }                 || 'top level must be a mapping'
        'file deleted'    | { Path f -> Files.delete(f) }             || 'cannot read'
    }

    def "the same broken content is reported once, not on every tick"() {
        given:
        file.text = VALID
        def store = store()
        file.text = 'flags: ['

        when:
        store.reload()
        def firstError = store.lastError()

        then:
        store.reload() == FAILED
        store.lastError().is(firstError)

        when: 'a different broken version arrives'
        file.text = 'flags:\n  x: {}'

        then:
        store.reload() == FAILED
        !store.lastError().is(firstError)
        store.lastError().message().contains("'enabled' is required")
    }

    def "lastSuccessfulCheckAt moves on every good check, not on failures"() {
        given:
        file.text = VALID
        def store = store()
        def atStartup = store.lastSuccessfulCheckAt()

        expect:
        atStartup == store.snapshot().loadedAt()

        when: 'an unchanged check'
        Thread.sleep(5)
        store.reload()
        def afterUnchanged = store.lastSuccessfulCheckAt()

        then:
        afterUnchanged > atStartup

        when: 'a failed check'
        file.text = 'flags: ['
        store.reload()

        then:
        store.lastSuccessfulCheckAt() == afterUnchanged
    }

    def "an unreadable file is reported once, not on every tick"() {
        given:
        file.text = VALID
        def store = store()
        Files.delete(file)

        when:
        store.reload()
        def firstError = store.lastError()

        then:
        firstError.message().contains('cannot read')
        store.reload() == FAILED
        store.lastError().is(firstError)

        when: 'the file comes back broken'
        file.text = 'flags: ['

        then:
        store.reload() == FAILED
        store.lastError().message().contains('malformed YAML')

        when: 'and then goes missing again'
        def brokenError = store.lastError()
        Files.delete(file)

        then:
        store.reload() == FAILED
        !store.lastError().is(brokenError)
        store.lastError().message().contains('cannot read')
    }

    def "Kubernetes ConfigMap style symlink swap is detected"() {
        given: 'flags.yaml -> ..data/flags.yaml, ..data -> ..v1'
        def v1 = Files.createDirectory(dir.resolve('..v1'))
        def v2 = Files.createDirectory(dir.resolve('..v2'))
        v1.resolve('flags.yaml').text = VALID
        v2.resolve('flags.yaml').text = VALID.replace('rollout: 25', 'rollout: 75')
        // Same mtime on both versions so only the content hash can tell them apart.
        v2.resolve('flags.yaml').toFile().setLastModified(v1.resolve('flags.yaml').toFile().lastModified())
        Files.createSymbolicLink(dir.resolve('..data'), v1.fileName)
        Files.createSymbolicLink(file, dir.fileSystem.getPath('..data', 'flags.yaml'))
        def store = store()

        expect:
        store.snapshot().flags()['new-checkout'].rollout() == 25

        when: 'the ..data link is atomically repointed, as kubelet does'
        def tmp = dir.resolve('..data_tmp')
        Files.createSymbolicLink(tmp, v2.fileName)
        Files.move(tmp, dir.resolve('..data'), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

        then:
        store.reload() == RELOADED
        store.snapshot().flags()['new-checkout'].rollout() == 75
    }
}
