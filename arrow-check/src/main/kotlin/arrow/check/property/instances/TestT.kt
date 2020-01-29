package arrow.check.property.instances

import arrow.Kind
import arrow.Kind2
import arrow.check.property.*
import arrow.check.property.instances.testt.monad.flatMap
import arrow.check.property.instances.testt.monad.monad
import arrow.check.property.instances.testt.monadTrans.liftT
import arrow.check.property.log.monoid.monoid
import arrow.core.Either
import arrow.core.Tuple2
import arrow.extension
import arrow.fx.IO
import arrow.fx.RacePair
import arrow.fx.RaceTriple
import arrow.fx.mtl.concurrent
import arrow.fx.mtl.eithert.async.async
import arrow.fx.mtl.eithert.monadDefer.monadDefer
import arrow.fx.mtl.writert.async.async
import arrow.fx.mtl.writert.monadDefer.monadDefer
import arrow.fx.typeclasses.*
import arrow.mtl.*
import arrow.mtl.extensions.eithert.functor.unit
import arrow.mtl.extensions.writert.applicative.applicative
import arrow.mtl.extensions.writert.functor.functor
import arrow.mtl.extensions.writert.monad.monad
import arrow.mtl.extensions.writert.monadError.monadError
import arrow.mtl.typeclasses.MonadReader
import arrow.mtl.typeclasses.MonadTrans
import arrow.mtl.typeclasses.MonadWriter
import arrow.syntax.function.andThen
import arrow.typeclasses.*
import kotlin.coroutines.CoroutineContext

@extension
interface TestTFunctor<M> : Functor<TestTPartialOf<M>> {
    fun MM(): Monad<M>

    override fun <A, B> Kind<TestTPartialOf<M>, A>.map(f: (A) -> B): Kind<TestTPartialOf<M>, B> =
        fix().map(MM(), f)
}

@extension
interface TestTApplicative<M> : Applicative<TestTPartialOf<M>> {
    fun MM(): Monad<M>

    override fun <A, B> Kind<TestTPartialOf<M>, A>.ap(ff: Kind<TestTPartialOf<M>, (A) -> B>): Kind<TestTPartialOf<M>, B> =
        fix().ap(MM(), ff.fix())

    override fun <A> just(a: A): Kind<TestTPartialOf<M>, A> = TestT.just(MM(), a)
}

@extension
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

@extension
interface TestTAlternative<M> : Alternative<TestTPartialOf<M>>, TestTApplicative<M> {
    override fun MM(): Monad<M>
    fun AF(): Alternative<M>
    override fun <A> empty(): Kind<TestTPartialOf<M>, A> = AF().empty<A>().liftT(MM())
    override fun <A> Kind<TestTPartialOf<M>, A>.orElse(b: Kind<TestTPartialOf<M>, A>): Kind<TestTPartialOf<M>, A> =
        TestT(EitherT(WriterT(AF().run { fix().runTestT.value().value().orElse(b.fix().runTestT.value().value()) })))
}

fun <M> TestT.Companion.monadTest(MM: Monad<M>): MonadTest<TestTPartialOf<M>> = object : TestTMonadTest<M> {
    override fun MM(): Monad<M> = MM
}

interface TestTMonadTest<M> : MonadTest<TestTPartialOf<M>>, TestTMonad<M> {
    override fun MM(): Monad<M>

    override fun <A> Test<A>.liftTest(): Kind<TestTPartialOf<M>, A> = hoist(MM())
}

@extension
interface TestTMonadTrans : MonadTrans<ForTestT> {
    override fun <G, A> Kind<G, A>.liftT(MF: Monad<G>): Kind2<ForTestT, G, A> = TestT(
        EitherT.liftF<WriterTPartialOf<G, Log>, Failure, A>(
            WriterT.functor(MF),
            WriterT.liftF(this, Log.monoid(), MF)
        )
    )
}

@extension
interface TestTMonadIO<M> : MonadIO<TestTPartialOf<M>>, TestTMonad<M> {
    override fun MM(): Monad<M> = MIO()
    fun MIO(): MonadIO<M>
    override fun <A> IO<A>.liftIO(): Kind<TestTPartialOf<M>, A> = MIO().run {
        liftIO().liftT(this)
    }
}

@extension
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
        ME().raiseError<A>(e).liftT(ME())
}

@extension
interface TestTMonadError<M, E> : MonadError<TestTPartialOf<M>, E>, TestTApplicativeError<M, E>, TestTMonad<M> {
    override fun MM(): Monad<M> = ME()
    override fun ME(): MonadError<M, E>
    override fun <A, B> Kind<TestTPartialOf<M>, A>.ap(ff: Kind<TestTPartialOf<M>, (A) -> B>): Kind<TestTPartialOf<M>, B> =
        fix().ap(ME(), ff.fix())
    override fun <A> just(a: A): Kind<TestTPartialOf<M>, A> = TestT.just(ME(), a)
}

// TODO when https://github.com/arrow-kt/arrow/pull/1981 is merged add MonadState etc

