package arrow.check.property

import arrow.check.gen.Gen
import arrow.check.pretty.showPretty
import arrow.typeclasses.Show
import pretty.Doc

// -------------- Property
fun property(config: PropertyConfig = PropertyConfig(), prop: suspend PropertyTest.() -> Unit): Property = Property(config, prop)

data class Property(val config: PropertyConfig, val prop: suspend PropertyTest.() -> Unit) {

    fun mapConfig(f: (PropertyConfig) -> PropertyConfig): Property =
        copy(config = f(config))

    fun withTests(i: Int): Property =
        mapConfig {
            PropertyConfig.terminationCriteria.modify(it) {
                val tl = TestLimit(i)
                when (it) {
                    is EarlyTermination -> EarlyTermination(it.confidence, tl)
                    is NoEarlyTermination -> NoEarlyTermination(it.confidence, tl)
                    is NoConfidenceTermination -> NoConfidenceTermination(tl)
                }
            }
        }

    fun withConfidence(c: Confidence): Property =
        mapConfig {
            PropertyConfig.terminationCriteria.modify(it) {
                when (it) {
                    is EarlyTermination -> EarlyTermination(c, it.limit)
                    is NoEarlyTermination -> NoEarlyTermination(c, it.limit)
                    is NoConfidenceTermination -> NoEarlyTermination(c, it.limit)
                }
            }
        }

    fun verifiedTermination(): Property =
        mapConfig {
            PropertyConfig.terminationCriteria.modify(it) {
                when (it) {
                    is EarlyTermination -> it
                    is NoEarlyTermination -> EarlyTermination(it.confidence, it.limit)
                    is NoConfidenceTermination -> EarlyTermination(Confidence(), it.limit)
                }
            }
        }

    fun withTerminationCriteria(i: TerminationCriteria): Property =
        mapConfig { PropertyConfig.terminationCriteria.set(it, i) }

    fun withDiscardLimit(i: Double): Property =
        mapConfig { PropertyConfig.maxDiscardRatio.set(it, DiscardRatio(i)) }

    fun withShrinkLimit(i: Int): Property =
        mapConfig { PropertyConfig.shrinkLimit.set(it, ShrinkLimit(i)) }

    fun once(): Property = withTests(1)

    companion object
}

interface PropertyTest : Test {

    suspend fun <R, A> forAllWith(showA: (A) -> Doc<Markup>, env: R, gen: Gen<R, A>): A

    suspend fun <A> forAllWith(showA: (A) -> Doc<Markup>, gen: Gen<Any?, A>): A =
        forAllWith(showA, Unit, gen)

    suspend fun <R, A> forAll(env: R, gen: Gen<R, A>, SA: Show<A> = Show.any()): A =
        forAllWith({ a -> a.showPretty(SA) }, env, gen)

    suspend fun <A> forAll(gen: Gen<Any?, A>, SA: Show<A> = Show.any()): A =
        forAll(Unit, gen, SA)

    suspend fun discard(): Nothing
}
