package arrow.check.property

import arrow.Kind
import arrow.check.gen.GenT
import arrow.check.gen.RandSeed
import arrow.check.gen.eqK
import arrow.check.gen.genK
import arrow.check.property.instances.alternative
import arrow.check.property.instances.monadError
import arrow.check.property.instances.monadReader
import arrow.check.property.instances.monadState
import arrow.core.Either
import arrow.core.EitherPartialOf
import arrow.core.ForId
import arrow.core.Id
import arrow.core.Tuple2
import arrow.core.extensions.either.eqK.eqK
import arrow.core.extensions.either.monad.monad
import arrow.core.extensions.either.monadError.monadError
import arrow.core.extensions.eq
import arrow.core.extensions.id.eqK.eqK
import arrow.core.extensions.id.monad.monad
import arrow.core.test.UnitSpec
import arrow.core.test.generators.GenK
import arrow.core.test.generators.genK
import arrow.core.test.generators.throwable
import arrow.core.test.laws.AlternativeLaws
import arrow.core.test.laws.MonadErrorLaws
import arrow.core.toT
import arrow.fx.test.eq.throwableEq
import arrow.mtl.Kleisli
import arrow.mtl.KleisliPartialOf
import arrow.mtl.StateT
import arrow.mtl.StateTPartialOf
import arrow.mtl.extensions.kleisli.monad.monad
import arrow.mtl.extensions.kleisli.monadReader.monadReader
import arrow.mtl.extensions.statet.monad.monad
import arrow.mtl.extensions.statet.monadState.monadState
import arrow.mtl.test.eq.eqK
import arrow.mtl.test.generators.genK
import arrow.mtl.test.laws.MonadReaderLaws
import arrow.mtl.test.laws.MonadStateLaws
import arrow.typeclasses.Eq
import arrow.typeclasses.EqK
import arrow.typeclasses.Monad
import io.kotlintest.properties.Gen

fun <M> PropertyT.Companion.genK(genK: GenK<M>, MM: Monad<M>) : GenK<PropertyTPartialOf<M>> =
    object : GenK<PropertyTPartialOf<M>> {
        override fun <A> genK(gen: Gen<A>): Gen<Kind<PropertyTPartialOf<M>, A>> =
            TestT.genK(GenT.genK(genK, MM)).genK(gen).map { PropertyT(it.fix()) }
    }

fun <M> PropertyT.Companion.eqK(eqK: EqK<M>, seedSize: Tuple2<RandSeed, Size>) : EqK<PropertyTPartialOf<M>> =
    object : EqK<PropertyTPartialOf<M>> {
        override fun <A> Kind<PropertyTPartialOf<M>, A>.eqK(other: Kind<PropertyTPartialOf<M>, A>, EQ: Eq<A>): Boolean =
            TestT.eqK(GenT.eqK(eqK, seedSize)).liftEq(EQ).run { fix().unPropertyT.eqv(other.fix().unPropertyT) }
    }

class PropertyTLawsTest : UnitSpec() {
    init {
        val zeroSeed = (RandSeed(0) toT Size(0))
        testLaws(
            MonadReaderLaws.laws(
                PropertyT.monadReader<KleisliPartialOf<Int, ForId>, Int>(Kleisli.monadReader(Id.monad())),
                PropertyT.genK<KleisliPartialOf<Int, ForId>>(Kleisli.genK(Id.genK()), Kleisli.monad(Id.monad())),
                Gen.int(),
                PropertyT.eqK(Kleisli.eqK(Id.eqK(), 1), zeroSeed),
                Int.eq()
            ),
            MonadStateLaws.laws(
                PropertyT.monadState<StateTPartialOf<Int, ForId>, Int>(StateT.monadState(Id.monad())),
                PropertyT.genK<StateTPartialOf<Int, ForId>>(StateT.genK(Id.genK(), Gen.int()), StateT.monad(Id.monad())),
                Gen.int(),
                PropertyT.eqK(StateT.eqK(Id.eqK(), Int.eq(), 1), zeroSeed),
                Int.eq()
            ),
            MonadErrorLaws.laws<PropertyTPartialOf<EitherPartialOf<Throwable>>>(
                PropertyT.monadError(Either.monadError()),
                PropertyT.genK(Either.genK(Gen.throwable()), Either.monad()),
                PropertyT.eqK(Either.eqK(throwableEq()), zeroSeed)
            ),
            AlternativeLaws.laws(
                PropertyT.alternative(Id.monad()),
                PropertyT.genK(Id.genK(), Id.monad()),
                PropertyT.eqK(Id.eqK(), zeroSeed)
            )
        )
    }
}
