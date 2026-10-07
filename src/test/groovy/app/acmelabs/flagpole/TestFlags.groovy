package app.acmelabs.flagpole

import java.nio.file.Files
import java.nio.file.Path

/** Writes a known flags file for the HTTP-level specs. */
class TestFlags {

    static final String CONTENT = '''\
        flags:
          new-checkout:
            enabled: true
            rollout: 25
            allow: [user_42]
            description: "New checkout flow"
          dark-mode:
            enabled: false
          everyone:
            enabled: true
        '''.stripIndent()

    static Map<String, String> properties() {
        Path dir = Files.createTempDirectory('flagpole-test')
        dir.toFile().deleteOnExit()
        Path file = dir.resolve('flags.yaml')
        file.text = CONTENT
        file.toFile().deleteOnExit()
        ['flags.file': file.toString(), 'flags.reload-enabled': 'false']
    }
}
