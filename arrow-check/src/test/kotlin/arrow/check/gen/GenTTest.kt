package arrow.check.gen

import arrow.Kind
import arrow.check.gen.instances.alternative
import arrow.check.gen.instances.eq
import arrow.check.gen.instances.monad
import arrow.check.gen.instances.monadError
import arrow.check.gen.instances.monadReader
import arrow.check.gen.instances.monadState
import arrow.check.gen.instances.monadTrans
import arrow.check.gen.instances.monadWriter
import arrow.check.gen.instances.monoid
import arrow.check.property.Size
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
import arrow.core.extensions.monoid
import arrow.mtl.OptionT
import arrow.mtl.extensions.optiont.eqK.eqK
import arrow.core.test.UnitSpec
import arrow.core.test.generators.GenK
import arrow.core.test.generators.genK
import arrow.core.test.generators.throwable
import arrow.core.test.laws.AlternativeLaws
import arrow.core.test.laws.MonadErrorLaws
import arrow.mtl.test.laws.MonadTransLaws
import arrow.core.test.laws.MonoidLaws
import arrow.core.toT
import arrow.mtl.Kleisli
import arrow.mtl.KleisliPartialOf
import arrow.mtl.StateT
import arrow.mtl.StateTPartialOf
import arrow.mtl.WriterT
import arrow.mtl.extensions.kleisli.monad.monad
import arrow.mtl.extensions.kleisli.monadReader.monadReader
import arrow.mtl.extensions.statet.monad.monad
import arrow.mtl.extensions.statet.monadState.monadState
import arrow.mtl.extensions.writert.eqK.eqK
import arrow.mtl.extensions.writert.monad.monad
import arrow.mtl.extensions.writert.monadWriter.monadWriter
import arrow.mtl.test.eq.eqK
import arrow.mtl.test.generators.genK
import arrow.mtl.test.laws.MonadReaderLaws
import arrow.mtl.test.laws.MonadStateLaws
import arrow.mtl.test.laws.MonadWriterLaws
import arrow.typeclasses.Eq
import arrow.typeclasses.EqK
import arrow.typeclasses.Monad
import io.kotlintest.properties.Gen

class GenTLawsSpec : UnitSpec() {
    init {
        val zeroSeed = (RandSeed(0) toT Size(0))
        testLaws(
            MonadErrorLaws.laws<GenTPartialOf<EitherPartialOf<Throwable>>>(
                GenT.monadError(Either.monadError()),
                GenT.genK(Either.genK(Gen.throwable()), Either.monad()),
                GenT.eqK(Either.eqK(Eq<Throwable> { a, b -> a::class == b::class }), zeroSeed)
            ),
            MonadReaderLaws.laws(
                GenT.monadReader<KleisliPartialOf<Int, ForId>, Int>(Kleisli.monadReader(Id.monad())),
                GenT.genK<KleisliPartialOf<Int, ForId>>(Kleisli.genK(Id.genK()), Kleisli.monad(Id.monad())),
                Gen.int(),
                GenT.eqK(Kleisli.eqK(Id.eqK(), 1), zeroSeed),
                Int.eq()
            ),
            MonadWriterLaws.laws(
                GenT.monadWriter(WriterT.monadWriter(Id.monad(), String.monoid())),
                String.monoid(),
                Gen.string(),
                GenT.genK(WriterT.genK(Id.genK(), Gen.string()), WriterT.monad(Id.monad(), String.monoid())),
                GenT.eqK(WriterT.eqK(Id.eqK(), String.eq()), zeroSeed),
                String.eq()
            ),
            MonadStateLaws.laws(
                GenT.monadState<StateTPartialOf<Int, ForId>, Int>(StateT.monadState(Id.monad())),
                GenT.genK<StateTPartialOf<Int, ForId>>(StateT.genK(Id.genK(), Gen.int()), StateT.monad(Id.monad())),
                Gen.int(),
                GenT.eqK(StateT.eqK(Id.eqK(), Int.eq(), 1), zeroSeed),
                Int.eq()
            ),
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
        genKF.genK(gen).map { GenT.monadTrans().run { it.liftT(MM) } }
}

/**
 * Don't attempt this with huge/infinite rose trees.
 */
fun <M> GenT.Companion.eqK(eqKF: EqK<M>, seedSize: Tuple2<RandSeed, Size>): EqK<GenTPartialOf<M>> = object : EqK<GenTPartialOf<M>> {
    override fun <A> Kind<GenTPartialOf<M>, A>.eqK(other: Kind<GenTPartialOf<M>, A>, EQ: Eq<A>): Boolean =
        Rose.eq(OptionT.eqK(eqKF), EQ).run { fix().runGen(seedSize).eqv(other.fix().runGen(seedSize)) }
}
