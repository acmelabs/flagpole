package app.acmelabs.flagpole.loader

import spock.lang.Specification
import spock.lang.Subject

class FlagFileParserSpec extends Specification {

    @Subject
    FlagFileParser parser = new FlagFileParser()

    def "parses a valid file with defaults applied"() {
        when:
        def snapshot = parser.parse('''
            flags:
              new-checkout:
                enabled: true
                rollout: 25
                allow: [user_42]
                description: "New checkout flow"
              dark-mode:
                enabled: false
            '''.stripIndent().bytes)

        then:
        snapshot.flags().keySet() as List == ['dark-mode', 'new-checkout']
        with(snapshot.flags()['new-checkout']) {
            enabled()
            rollout() == 25
            allow() == ['user_42'] as Set
            description() == 'New checkout flow'
        }
        with(snapshot.flags()['dark-mode']) {
            !enabled()
            rollout() == 100
            allow().isEmpty()
            description() == null
        }
        snapshot.version() ==~ /[0-9a-f]{64}/
    }

    def "version is the content hash"() {
        given:
        byte[] a = 'flags:\n  x:\n    enabled: true\n'.bytes
        byte[] b = 'flags:\n  x:\n    enabled: false\n'.bytes

        expect:
        parser.parse(a).version() == parser.parse(a.clone()).version()
        parser.parse(a).version() != parser.parse(b).version()
    }

    def "empty flags mapping is valid"() {
        expect:
        parser.parse(content.bytes).flags().isEmpty()

        where:
        content << ['flags: {}', 'flags:']
    }

    def "valid flag name '#name'"() {
        expect:
        parser.parse("flags:\n  '$name':\n    enabled: true\n".bytes).flags().containsKey(name)

        where:
        name << ['a', '0', 'new-checkout', 'search.v2', 'beta_reports', 'a-b_c.d9']
    }

    def "only true/false are booleans, YAML 1.1 yes/no/on/off stay strings"() {
        when:
        def flags = parser.parse('''
            flags:
              on:
                enabled: True
                allow: [no, yes, NO, off]
              x:
                enabled: FALSE
            '''.stripIndent().bytes).flags()

        then:
        flags['on'].enabled()
        flags['on'].allow() == ['no', 'yes', 'NO', 'off'] as Set
        !flags['x'].enabled()
    }

    def "rejects #problem"() {
        when:
        parser.parse(content.bytes)

        then:
        def e = thrown(InvalidFlagsException)
        e.errors.any { it.contains(message) }

        where:
        problem                   | content                                                     || message
        'malformed YAML'          | 'flags: [unclosed'                                          || 'malformed YAML'
        'non-mapping root'        | '- a\n- b'                                                  || 'top level must be a mapping'
        'empty file'              | ''                                                          || 'top level must be a mapping'
        'missing flags key'       | 'other: 1'                                                  || "missing required top-level key 'flags'"
        'unknown top-level key'   | 'flags: {}\nextra: 1'                                       || "unknown top-level key 'extra'"
        'flags as a list'         | 'flags: [a, b]'                                             || "'flags' must be a mapping"
        'uppercase name'          | 'flags:\n  New:\n    enabled: true'                         || "flag name 'New'"
        'name with leading dash'  | 'flags:\n  -x:\n    enabled: true'                          || "flag name '-x'"
        'name with space'         | 'flags:\n  "a b":\n    enabled: true'                       || "flag name 'a b'"
        'numeric name'            | 'flags:\n  42:\n    enabled: true'                          || "flag name '42'"
        'non-mapping definition'  | 'flags:\n  x: true'                                         || 'definition must be a mapping'
        'missing enabled'         | 'flags:\n  x:\n    rollout: 5'                              || "'enabled' is required"
        'non-boolean enabled'     | 'flags:\n  x:\n    enabled: "yes please"'                   || "'enabled' must be true or false"
        'YAML 1.1 yes as enabled' | 'flags:\n  x:\n    enabled: yes'                          || "'enabled' must be true or false"
        'YAML 1.1 off as enabled' | 'flags:\n  x:\n    enabled: off'                          || "'enabled' must be true or false"
        'rollout above 100'       | 'flags:\n  x:\n    enabled: true\n    rollout: 101'         || "'rollout' must be an integer between 0 and 100"
        'negative rollout'        | 'flags:\n  x:\n    enabled: true\n    rollout: -1'          || "'rollout' must be an integer between 0 and 100"
        'fractional rollout'      | 'flags:\n  x:\n    enabled: true\n    rollout: 2.5'         || "'rollout' must be an integer between 0 and 100"
        'string rollout'          | 'flags:\n  x:\n    enabled: true\n    rollout: "50"'        || "'rollout' must be an integer between 0 and 100"
        'allow not a list'        | 'flags:\n  x:\n    enabled: true\n    allow: user_1'        || "'allow' must be a list"
        'numeric allow entry'     | 'flags:\n  x:\n    enabled: true\n    allow: [42]'          || "'allow' entries must be non-blank strings"
        'blank allow entry'       | 'flags:\n  x:\n    enabled: true\n    allow: [" "]'         || "'allow' entries must be non-blank strings"
        'non-string description'  | 'flags:\n  x:\n    enabled: true\n    description: [a]'     || "'description' must be a string"
        'unknown flag key (typo)' | 'flags:\n  x:\n    enabled: true\n    rolout: 5'            || "unknown key 'rolout'"
        'duplicate flag'          | 'flags:\n  x:\n    enabled: true\n  x:\n    enabled: false' || 'malformed YAML'
    }

    def "reports every error at once"() {
        when:
        parser.parse('flags:\n  Bad:\n    enabled: true\n  y:\n    rollout: 200\n'.bytes)

        then:
        def e = thrown(InvalidFlagsException)
        e.errors.size() == 3
    }
}
