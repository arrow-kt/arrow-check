package arrow.check.property

import arrow.check.gen.Fun
import arrow.check.gen.Gen
import arrow.check.gen.GenT
import arrow.check.gen.GenTPartialOf
import arrow.check.gen.fix
import arrow.check.gen.generalize
import arrow.check.gen.instances.functor
import arrow.check.gen.instances.monad
import arrow.check.gen.monadGen
import arrow.check.gen.show
import arrow.check.pretty.showPretty
import arrow.check.property.instances.monadTest
import arrow.check.property.instances.monadTrans
import arrow.core.Id
import arrow.core.extensions.id.monad.monad
import arrow.fx.ForIO
import arrow.mtl.EitherT
import arrow.mtl.WriterT
import arrow.mtl.WriterTPartialOf
import arrow.mtl.extensions.writert.functor.functor
import arrow.typeclasses.Monad
import arrow.typeclasses.Show
import pretty.Doc

// -------------- Property
data class Property(val config: PropertyConfig, val prop: PropertyT<ForIO, Unit>) {

    fun mapConfig(f: (PropertyConfig) -> PropertyConfig): Property =
        copy(config = f(config))

    fun withTests(i: TestLimit): Property =
        mapConfig {
            PropertyConfig.terminationCriteria.modify(it) {
                when (it) {
                    is EarlyTermination -> EarlyTermination(it.confidence, i)
                    is NoEarlyTermination -> NoEarlyTermination(it.confidence, i)
                    is NoConfidenceTermination -> NoConfidenceTermination(i)
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

    fun withDiscardLimit(i: DiscardRatio): Property =
        mapConfig { PropertyConfig.maxDiscardRatio.set(it, i) }

    fun withShrinkLimit(i: ShrinkLimit): Property =
        mapConfig { PropertyConfig.shrinkLimit.set(it, i) }

    fun withShrinkRetries(i: ShrinkRetries): Property =
        mapConfig { PropertyConfig.shrinkRetries.set(it, i) }

    companion object
}

// @higherkind boilerplate
class ForPropertyT private constructor() {
    companion object
}
typealias PropertyTOf<M, A> = arrow.Kind<PropertyTPartialOf<M>, A>
typealias PropertyTPartialOf<M> = arrow.Kind<ForPropertyT, M>

@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
inline fun <M, A> PropertyTOf<M, A>.fix(): PropertyT<M, A> =
    this as PropertyT<M, A>

data class PropertyT<M, A>(val unPropertyT: TestT<GenTPartialOf<M>, A>) : PropertyTOf<M, A> {

    fun <B> map(MM: Monad<M>, f: (A) -> B): PropertyT<M, B> = PropertyT(unPropertyT.map(GenT.monad(MM), f))

    fun <B> ap(MM: Monad<M>, ff: PropertyT<M, (A) -> B>): PropertyT<M, B> =
        PropertyT(unPropertyT.ap(Gen.monad(MM), ff.unPropertyT))

    companion object
}

// ---------

// these will be specialised later in syntax interfaces so now worries here
fun <M, A> forAllWithT(showA: (A) -> Doc<Markup>, gen: GenT<M, A>, MM: Monad<M>): PropertyT<M, A> =
    PropertyT.monadTest(MM).run {
        PropertyT(TestT.monadTrans().run { gen.liftT(GenT.monad(MM)).fix() }).flatTap { a ->
            writeLog(JournalEntry.Input { showA(a) })
        }.fix()
    }

fun <M, A> forAllWith(showA: (A) -> Doc<Markup>, gen: Gen<A>, MM: Monad<M>): PropertyT<M, A> =
    forAllWithT(showA, gen.generalize(MM), MM)

fun <M, A> forAllT(gen: GenT<M, A>, MM: Monad<M>, SA: Show<A> = Show.any()): PropertyT<M, A> =
    forAllWithT({ SA.run { it.showPretty(SA) } }, gen, MM)

fun <M, A> forAll(gen: Gen<A>, MM: Monad<M>, SA: Show<A> = Show.any()): PropertyT<M, A> =
    forAllT(gen.generalize(MM), MM, SA)

fun <M, A> discard(MM: Monad<M>): PropertyT<M, A> =
    PropertyT(
        TestT(
            EitherT.liftF<Failure, WriterTPartialOf<Log, GenTPartialOf<M>>, A>(
                WriterT.functor(GenT.functor(MM)),
                WriterT.liftF(
                    GenT.monadGen(Id.monad()).discard<A>().fix().generalize(MM),
                    Log.monoid(),
                    GenT.monad(MM)
                )
            )
        )
    )

fun <M, A, B> forAllFn(
  gen: Gen<Fun<A, B>>,
  MM: Monad<M>,
  SA: Show<A> = Show.any(),
  SB: Show<B> = Show.any()
): PropertyT<M, (A) -> B> =
    forAll(gen, MM, Fun.show(SA, SB)).map(MM) { it.component1() }
