package arrow.check.pretty

import arrow.core.test.UnitSpec
import arrow.core.test.laws.EqLaws
import io.kotlintest.properties.Gen

/**
 * This should use more recursive generators to generate the other cases but since kotlintest cannot guarantee termination
 *  I won't bother
 */
fun KValue.Companion.gen(): Gen<KValue> = Gen.oneOf(
    Gen.long().map { KValue.Decimal(it) },
    Gen.string().map { KValue.RawString(it) },
    Gen.double().map { KValue.Rational(it) }
)

class KValueLawsTest : UnitSpec() {
    init {
        testLaws(EqLaws.laws(KValue.eq(), KValue.gen()))
    }
}
