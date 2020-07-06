package arrow.check.property.instances

import arrow.Kind
import arrow.Kind2
import arrow.check.property.Failure
import arrow.check.property.ForTestT
import arrow.check.property.Log
import arrow.check.property.MonadTest
import arrow.check.property.Test
import arrow.check.property.TestT
import arrow.check.property.TestTPartialOf
import arrow.check.property.fix
import arrow.check.property.hoist
import arrow.check.property.monoid
import arrow.core.Either
import arrow.fx.IO
import arrow.fx.typeclasses.MonadIO
import arrow.mtl.EitherT
import arrow.mtl.WriterT
import arrow.mtl.WriterTPartialOf
import arrow.mtl.extensions.writert.applicative.applicative
import arrow.mtl.extensions.writert.functor.functor
import arrow.mtl.extensions.writert.monad.monad
import arrow.mtl.extensions.writert.monadError.monadError
import arrow.mtl.typeclasses.MonadTrans
import arrow.mtl.value
import arrow.syntax.function.andThen
import arrow.typeclasses.Alternative
import arrow.typeclasses.Applicative
import arrow.typeclasses.ApplicativeError
import arrow.typeclasses.Functor
import arrow.typeclasses.Monad
import arrow.typeclasses.MonadError

// @extension
interface TestTFunctor<M> : Functor<TestTPartialOf<M>> {
    fun MM(): Monad<M>

    override fun <A, B> Kind<TestTPartialOf<M>, A>.map(f: (A) -> B): Kind<TestTPartialOf<M>, B> =
        fix().map(MM(), f)
}

fun <M> TestT.Companion.functor(MM: Monad<M>): Functor<TestTPartialOf<M>> = object : TestTFunctor<M> {
    override fun MM(): Monad<M> = MM
}

// @extension
interface TestTApplicative<M> : Applicative<TestTPartialOf<M>> {
    fun MM(): Monad<M>

    override fun <A, B> Kind<TestTPartialOf<M>, A>.ap(ff: Kind<TestTPartialOf<M>, (A) -> B>): Kind<TestTPartialOf<M>, B> =
        fix().ap(MM(), ff.fix())

    override fun <A> just(a: A): Kind<TestTPartialOf<M>, A> = TestT.just(MM(), a)
}

fun <M> TestT.Companion.applicative(MM: Monad<M>): Applicative<TestTPartialOf<M>> = object : TestTApplicative<M> {
    override fun MM(): Monad<M> = MM
}

// @extension
interface TestTMonad<M> : Monad<TestTPartialOf<M>> {
    fun MM(): Monad<M>

    override fun <A> just(a: A): Kind<TestTPartialOf<M>, A> =
        TestT(EitherT.just(WriterT.applicative(MM(), Log.monoid()), a))

    override fun <A, B> Kind<TestTPartialOf<M>, A>.flatMap(f: (A) -> Kind<TestTPartialOf<M>, B>): Kind<TestTPartialOf<M>, B> =
        TestT(fix().runTestT.flatMap(WriterT.monad(MM(), Log.monoid()), f andThen { it.fix().runTestT }))

    override fun <A, B> tailRecM(a: A, f: (A) -> Kind<TestTPartialOf<M>, Either<A, B>>): Kind<TestTPartialOf<M>, B> =
        f(a).flatMap {
            it.fold({
                tailRecM(it, f)
            }, {
                just(it)
            })
        }
}

fun <M> TestT.Companion.monad(MM: Monad<M>): Monad<TestTPartialOf<M>> = object : TestTMonad<M> {
    override fun MM(): Monad<M> = MM
}

// @extension
interface TestTAlternative<M> : Alternative<TestTPartialOf<M>>, TestTApplicative<M> {
    override fun MM(): Monad<M>
    fun AF(): Alternative<M>
    override fun <A> empty(): Kind<TestTPartialOf<M>, A> = TestT.monadTrans().run { AF().empty<A>().liftT(MM()) }
    override fun <A> Kind<TestTPartialOf<M>, A>.orElse(b: Kind<TestTPartialOf<M>, A>): Kind<TestTPartialOf<M>, A> =
        TestT(EitherT(WriterT(AF().run { fix().runTestT.value().value().orElse(b.fix().runTestT.value().value()) })))
}

fun <M> TestT.Companion.alternative(MM: Monad<M>, AF: Alternative<M>): Alternative<TestTPartialOf<M>> =
    object : TestTAlternative<M> {
        override fun AF(): Alternative<M> = AF
        override fun MM(): Monad<M> = MM
    }

interface TestTMonadTest<M> : MonadTest<TestTPartialOf<M>>, TestTMonad<M> {

    override fun MM(): Monad<M>
    override fun <A> Test<A>.liftTest(): Kind<TestTPartialOf<M>, A> = hoist(MM())
}

fun <M> TestT.Companion.monadTest(MM: Monad<M>): MonadTest<TestTPartialOf<M>> = object : TestTMonadTest<M> {
    override fun MM(): Monad<M> = MM
}

// @extension
interface TestTMonadTrans : MonadTrans<ForTestT> {
    override fun <G, A> Kind<G, A>.liftT(MF: Monad<G>): Kind2<ForTestT, G, A> = TestT(
        EitherT.liftF<Failure, WriterTPartialOf<Log, G>, A>(
            WriterT.functor(MF),
            WriterT.liftF(this, Log.monoid(), MF)
        )
    )
}

fun TestT.Companion.monadTrans(): MonadTrans<ForTestT> = object : TestTMonadTrans {}

// @extension
interface TestTMonadIO<M> : MonadIO<TestTPartialOf<M>>, TestTMonad<M> {
    override fun MM(): Monad<M> = MIO()
    fun MIO(): MonadIO<M>
    override fun <A> IO<A>.liftIO(): Kind<TestTPartialOf<M>, A> = MIO().run {
        TestT.monadTrans().run {
            liftIO().liftT(MIO())
        }
    }
}

fun <M> TestT.Companion.monadIO(MIO: MonadIO<M>): MonadIO<TestTPartialOf<M>> = object : TestTMonadIO<M> {
    override fun MIO(): MonadIO<M> = MIO
}

// @extension
interface TestTApplicativeError<M, E> : ApplicativeError<TestTPartialOf<M>, E>, TestTApplicative<M> {
    override fun MM(): Monad<M> = ME()
    fun ME(): MonadError<M, E>
    override fun <A> Kind<TestTPartialOf<M>, A>.handleErrorWith(f: (E) -> Kind<TestTPartialOf<M>, A>): Kind<TestTPartialOf<M>, A> =
        WriterT.monadError(ME(), Log.monoid()).run {
            TestT(EitherT(
                fix().runTestT.value().handleErrorWith {
                    f(it).fix().runTestT.value()
                }
            ))
        }

    override fun <A> raiseError(e: E): Kind<TestTPartialOf<M>, A> =
        TestT.monadTrans().run {
            ME().raiseError<A>(e).liftT(ME())
        }
}

fun <M, E> TestT.Companion.applicativeError(ME: MonadError<M, E>): ApplicativeError<TestTPartialOf<M>, E> =
    object : TestTApplicativeError<M, E> {
        override fun ME(): MonadError<M, E> = ME
    }

// @extension
interface TestTMonadError<M, E> : MonadError<TestTPartialOf<M>, E>, TestTApplicativeError<M, E>, TestTMonad<M> {
    override fun MM(): Monad<M> = ME()
    override fun ME(): MonadError<M, E>
    override fun <A, B> Kind<TestTPartialOf<M>, A>.ap(ff: Kind<TestTPartialOf<M>, (A) -> B>): Kind<TestTPartialOf<M>, B> =
        fix().ap(ME(), ff.fix())

    override fun <A> just(a: A): Kind<TestTPartialOf<M>, A> = TestT.just(ME(), a)
}

fun <M, E> TestT.Companion.monadError(ME: MonadError<M, E>): MonadError<TestTPartialOf<M>, E> =
    object : TestTMonadError<M, E> {
        override fun ME(): MonadError<M, E> = ME
    }

// TODO when https://github.com/arrow-kt/arrow/pull/1981 is merged add MonadState etc
