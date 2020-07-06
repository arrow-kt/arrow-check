package arrow.check.gen

import arrow.Kind
import arrow.check.gen.instances.applicative
import arrow.check.gen.instances.eq
import arrow.check.gen.instances.eqK
import arrow.check.gen.instances.functor
import arrow.check.gen.instances.monad
import arrow.check.gen.instances.monadError
import arrow.check.gen.instances.monadTrans
import arrow.check.gen.instances.traverse
import arrow.core.Either
import arrow.core.ForId
import arrow.core.Id
import arrow.core.SequenceK
import arrow.core.extensions.either.eqK.eqK
import arrow.core.extensions.either.monad.monad
import arrow.core.extensions.either.monadError.monadError
import arrow.core.extensions.eq
import arrow.core.extensions.id.eqK.eqK
import arrow.core.extensions.id.monad.monad
import arrow.core.fix
import arrow.core.test.UnitSpec
import arrow.core.test.generators.GenK
import arrow.core.test.generators.genK
import arrow.core.test.generators.throwable
import arrow.core.test.laws.EqKLaws
import arrow.core.test.laws.EqLaws
import arrow.core.test.laws.MonadErrorLaws
import arrow.core.test.laws.TraverseLaws
import arrow.typeclasses.Eq
import arrow.typeclasses.Monad

class RoseLawsSpec : UnitSpec() {
    init {
        testLaws(
            MonadErrorLaws.laws(
                Rose.monadError(Either.monadError<Throwable>()),
                Rose.functor(Either.monad<Throwable>()),
                Rose.applicative(Either.monad<Throwable>()),
                Rose.monad(Either.monad<Throwable>()),
                Rose.genK(Either.genK(io.kotlintest.properties.Gen.throwable()), Either.monad()),
                Rose.eqK(Either.eqK(Eq<Throwable> { a, b -> a::class == b::class }))
            ),
            EqLaws.laws(
                Rose.eq(Id.eqK(), Int.eq()),
                Rose.genK(Id.genK(), Id.monad()).genK(io.kotlintest.properties.Gen.int()) as io.kotlintest.properties.Gen<Rose<ForId, Int>>
            ),
            EqKLaws.laws(
                Rose.eqK(Id.eqK()),
                Rose.genK(Id.genK(), Id.monad())
            ) // TODO test MonadState and MonadWriter laws as soon as genK and eqK become visible in arrow-test https://github.com/arrow-kt/arrow/pull/1981
        )
    }
}

class RoseFLawsSpec : UnitSpec() {
    init {
        testLaws(
            EqLaws.laws(
                RoseF.eq(Int.eq(), Int.eq()),
                RoseF.genK(io.kotlintest.properties.Gen.int()).genK(io.kotlintest.properties.Gen.int()) as io.kotlintest.properties.Gen<RoseF<Int, Int>>
            ),
            EqKLaws.laws(
                RoseF.eqK(Int.eq()),
                RoseF.genK(io.kotlintest.properties.Gen.int())
            ),
            TraverseLaws.laws(
                RoseF.traverse(),
                RoseF.genK(io.kotlintest.properties.Gen.int()),
                RoseF.eqK(Int.eq())
            )
        )
    }
}

fun <B> RoseF.Companion.genK(bGen: io.kotlintest.properties.Gen<B>): GenK<RoseFPartialOf<B>> = object : GenK<RoseFPartialOf<B>> {
    override fun <A> genK(gen: io.kotlintest.properties.Gen<A>): io.kotlintest.properties.Gen<Kind<RoseFPartialOf<B>, A>> =
        io.kotlintest.properties.Gen.bind(bGen, SequenceK.genK().genK(gen)) { a, b -> RoseF(a, b.fix()) }
}

// FIXME this should be a recusive gen, but with kotlintest that is not possible (with sufficient termination guarantees that is)
fun <M> Rose.Companion.genK(genK: GenK<M>, MM: Monad<M>): GenK<RosePartialOf<M>> = object : GenK<RosePartialOf<M>> {
    override fun <A> genK(gen: io.kotlintest.properties.Gen<A>): io.kotlintest.properties.Gen<Kind<RosePartialOf<M>, A>> =
        genK.genK(gen).map { Rose.monadTrans().run { it.liftT(MM) } }
}
