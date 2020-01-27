package arrow.check.gen

import arrow.Kind
import arrow.check.gen.instances.gent.alternative.alternative
import arrow.check.gen.instances.gent.monad.monad
import arrow.check.gen.instances.gent.monadTrans.liftT
import arrow.check.gen.instances.gent.monadTrans.monadTrans
import arrow.check.gen.instances.gent.monoid.monoid
import arrow.check.gen.instances.rose.eq.eq
import arrow.check.property.Size
import arrow.core.ForId
import arrow.core.Id
import arrow.core.Tuple2
import arrow.core.extensions.eq
import arrow.core.extensions.id.eqK.eqK
import arrow.core.extensions.id.monad.monad
import arrow.core.extensions.monoid
import arrow.core.toT
import arrow.mtl.OptionT
import arrow.mtl.extensions.optiont.eqK.eqK
import arrow.test.UnitSpec
import arrow.test.generators.GenK
import arrow.test.generators.genK
import arrow.test.laws.AlternativeLaws
import arrow.test.laws.MonadTransLaws
import arrow.test.laws.MonoidLaws
import arrow.typeclasses.Eq
import arrow.typeclasses.EqK
import arrow.typeclasses.Monad
import io.kotlintest.properties.Gen

class GenTLawsSpec : UnitSpec() {
    init {
        val zeroSeed = (RandSeed(0) toT Size(0))
        testLaws(
            /* TODO GenT<M, A> is not stacksafe it M isn't. Decide what this means for the choice of `M` in the default gen!
            MonadErrorLaws.laws<GenTPartialOf<EitherPartialOf<Throwable>>>(
                GenT.monadError(Either.monadError()),
                GenT.genK(Either.genK(Gen.throwable()), Either.monad()),
                GenT.eqK(Either.eqK(Eq<Throwable> { a, b -> a::class == b::class }), zeroSeed)
            ),*/
            MonadTransLaws.laws(
                GenT.monadTrans(),
                Id.monad(),
                GenT.monad(Id.monad()),
                Id.genK(),
                GenT.eqK(Id.eqK(), zeroSeed)
            ),
            AlternativeLaws.laws(
                GenT.alternative(Id.monad()),
                GenT.genK(Id.genK(), Id.monad()),
                GenT.eqK(Id.eqK(), zeroSeed)
            ),
            MonoidLaws.laws(
                GenT.monoid(Id.monad(), String.monoid()),
                GenT.genK(Id.genK(), Id.monad()).genK(Gen.string()) as Gen<GenT<ForId, String>>,
                GenT.eqK(Id.eqK(), zeroSeed).liftEq(String.eq()) as Eq<GenT<ForId, String>>
            )
        )
    }
}

fun <M> GenT.Companion.genK(genKF: GenK<M>, MM: Monad<M>): GenK<GenTPartialOf<M>> = object : GenK<GenTPartialOf<M>> {
    override fun <A> genK(gen: Gen<A>): Gen<Kind<GenTPartialOf<M>, A>> =
        genKF.genK(gen).map { it.liftT(MM) }
}

/**
 * Don't attempt this with huge/infinite rose trees.
 */
fun <M> GenT.Companion.eqK(eqKF: EqK<M>, seedSize: Tuple2<RandSeed, Size>): EqK<GenTPartialOf<M>> = object : EqK<GenTPartialOf<M>> {
    override fun <A> Kind<GenTPartialOf<M>, A>.eqK(other: Kind<GenTPartialOf<M>, A>, EQ: Eq<A>): Boolean =
        Rose.eq(OptionT.eqK(eqKF), EQ).run { fix().runGen(seedSize).eqv(other.fix().runGen(seedSize)) }
}
