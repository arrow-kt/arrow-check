package arrow.check.property

import arrow.Kind
import arrow.check.property.instances.alternative
import arrow.check.property.instances.monadError
import arrow.check.property.instances.monadReader
import arrow.check.property.instances.monadState
import arrow.core.Either
import arrow.core.EitherPartialOf
import arrow.core.ForId
import arrow.core.Id
import arrow.core.ListK
import arrow.core.Option
import arrow.core.extensions.either.eqK.eqK
import arrow.core.extensions.either.monadError.monadError
import arrow.core.extensions.eq
import arrow.core.extensions.id.eqK.eqK
import arrow.core.extensions.id.monad.monad
import arrow.core.extensions.option.alternative.alternative
import arrow.core.extensions.option.eqK.eqK
import arrow.core.extensions.option.monad.monad
import arrow.core.test.UnitSpec
import arrow.core.test.generators.GenK
import arrow.core.test.generators.genK
import arrow.core.test.generators.throwable
import arrow.core.test.laws.AlternativeLaws
import arrow.core.test.laws.MonadErrorLaws
import arrow.mtl.EitherT
import arrow.mtl.Kleisli
import arrow.mtl.KleisliPartialOf
import arrow.mtl.StateT
import arrow.mtl.StateTPartialOf
import arrow.mtl.WriterT
import arrow.mtl.extensions.kleisli.monadReader.monadReader
import arrow.mtl.extensions.statet.monadState.monadState
import arrow.mtl.fix
import arrow.mtl.test.eq.eqK
import arrow.mtl.test.generators.genK
import arrow.mtl.test.laws.MonadReaderLaws
import arrow.mtl.test.laws.MonadStateLaws
import arrow.typeclasses.Eq
import io.kotlintest.properties.Gen
import pretty.doc

fun <M> TestT.Companion.genK(genK: GenK<M>): GenK<TestTPartialOf<M>> = object : GenK<TestTPartialOf<M>> {
    override fun <A> genK(gen: Gen<A>): Gen<Kind<TestTPartialOf<M>, A>> =
        EitherT.genK(
            WriterT.genK(
                genK,
                Gen.create { Log(ListK.empty()) }
            ),
            Gen.string().map { Failure(it.doc()) }
        ).genK(gen).map { TestT(it.fix()) }
}

class TestTLawsTest : UnitSpec() {
    init {
        testLaws(
            MonadReaderLaws.laws(
                TestT.monadReader<KleisliPartialOf<Int, ForId>, Int>(Kleisli.monadReader(Id.monad())),
                TestT.genK(Kleisli.genK<Int, ForId>(Id.genK())),
                Gen.int(),
                TestT.eqK(Kleisli.eqK(Id.eqK(), 1)),
                Int.eq()
            ),
            MonadStateLaws.laws(
                TestT.monadState<StateTPartialOf<Int, ForId>, Int>(StateT.monadState(Id.monad())),
                TestT.genK(StateT.genK(Id.genK(), Gen.int())),
                Gen.int(),
                TestT.eqK(StateT.eqK(Id.eqK(), Int.eq(), 1)),
                Int.eq()
            ),
            MonadErrorLaws.laws<TestTPartialOf<EitherPartialOf<Throwable>>>(
                TestT.monadError(Either.monadError()),
                TestT.genK(Either.genK(Gen.throwable())),
                TestT.eqK(Either.eqK(Eq<Throwable> { a, b -> a::class == b::class }))
            ),
            AlternativeLaws.laws(
                TestT.alternative(Option.monad(), Option.alternative()),
                TestT.genK(Option.genK()),
                TestT.eqK(Option.eqK())
            )
        )
    }
}