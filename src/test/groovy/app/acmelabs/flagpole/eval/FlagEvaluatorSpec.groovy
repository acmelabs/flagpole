package app.acmelabs.flagpole.eval

import app.acmelabs.flagpole.model.FlagDefinition
import app.acmelabs.flagpole.model.FlagSnapshot
import spock.lang.Specification
import spock.lang.Subject

import java.time.Instant

class FlagEvaluatorSpec extends Specification {

    @Subject
    FlagEvaluator evaluator = new FlagEvaluator()

    static FlagDefinition flag(Map args = [:]) {
        new FlagDefinition(args.name ?: 'my-flag', args.containsKey('enabled') ? args.enabled : true,
                args.containsKey('rollout') ? args.rollout : 100, (args.allow ?: []) as Set, null)
    }

    def "evaluation rule: #rule"() {
        expect:
        evaluator.evaluate(definition, userId) == expected

        where:
        rule                                        | definition                                      | userId    || expected
        'disabled is false'                         | flag(enabled: false)                            | 'user_1'  || false
        'disabled beats allow list'                 | flag(enabled: false, allow: ['user_1'])         | 'user_1'  || false
        'allow list is true at rollout 0'           | flag(rollout: 0, allow: ['user_1'])             | 'user_1'  || true
        'not on allow list at rollout 0'            | flag(rollout: 0, allow: ['user_1'])             | 'user_2'  || false
        'rollout 0 is false'                        | flag(rollout: 0)                                | 'user_1'  || false
        'rollout 100 is true'                       | flag(rollout: 100)                              | 'user_1'  || true
        'rollout 100 is true without user'          | flag(rollout: 100)                              | null      || true
        'missing user with partial rollout'         | flag(rollout: 99)                               | null      || false
        'blank user with partial rollout'           | flag(rollout: 99)                               | '  '      || false
        'blank user does not match allow list'      | flag(rollout: 0, allow: [' '])                  | ' '       || false
    }

    def "partial rollout compares bucket against rollout"() {
        given:
        int bucket = FlagEvaluator.bucket('my-flag', 'user_1')

        expect:
        evaluator.evaluate(flag(rollout: bucket + 1), 'user_1')
        !evaluator.evaluate(flag(rollout: bucket), 'user_1')
    }

    def "bucket is the first 4 bytes of SHA-256(flag:user) as unsigned int mod 100"() {
        expect: 'reference values computed independently (e.g. with sha256sum)'
        FlagEvaluator.bucket(flagName, userId) == expected

        where:
        flagName       | userId    || expected
        'new-checkout' | 'user_1'  || referenceBucket('new-checkout', 'user_1')
        'new-checkout' | 'user_2'  || referenceBucket('new-checkout', 'user_2')
        'dark-mode'    | 'user_1'  || referenceBucket('dark-mode', 'user_1')
    }

    def "same flag and user always give the same result"() {
        given:
        def definition = flag(rollout: 50)

        expect:
        (1..100).collect { evaluator.evaluate(definition, userId) }.unique().size() == 1
        FlagEvaluator.bucket('my-flag', userId) == FlagEvaluator.bucket('my-flag', userId)

        where:
        userId << ['user_1', 'user_42', 'alice@example.com', 'ünïcødé']
    }

    def "bucket differs per flag so rollouts are independent"() {
        expect:
        (0..<1000).count { FlagEvaluator.bucket('flag-a', "u$it") != FlagEvaluator.bucket('flag-b', "u$it") } > 900
    }

    def "#rollout% rollout over 10,000 users lands within ±2%"() {
        given:
        def definition = flag(name: 'new-checkout', rollout: rollout)

        when:
        int enabled = (0..<10_000).count { evaluator.evaluate(definition, "user_$it") }

        then:
        Math.abs(enabled / 100.0 - rollout) <= 2

        where:
        rollout << [25, 10, 50, 75]
    }

    def "evaluateAll evaluates every flag in name order"() {
        given:
        def snapshot = new FlagSnapshot([
                'b-flag': flag(name: 'b-flag'),
                'a-flag': flag(name: 'a-flag', enabled: false)], 'v1', Instant.now())

        expect:
        evaluator.evaluateAll(snapshot, 'user_1') == ['a-flag': false, 'b-flag': true]
        evaluator.evaluateAll(snapshot, 'user_1').keySet() as List == ['a-flag', 'b-flag']
    }

    /** Independent implementation of the bucketing rule, using BigInteger instead of ByteBuffer. */
    static int referenceBucket(String flagName, String userId) {
        byte[] digest = java.security.MessageDigest.getInstance('SHA-256').digest("$flagName:$userId".getBytes('UTF-8'))
        new BigInteger(1, digest[0..3] as byte[]).mod(BigInteger.valueOf(100)).intValue()
    }
}
