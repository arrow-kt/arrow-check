package arrow.check.property.instances

import arrow.Kind
import arrow.Kind2
import arrow.check.property.*
import arrow.check.property.instances.testt.monad.flatMap
import arrow.check.property.instances.testt.monad.monad
import arrow.check.property.instances.testt.monadTrans.liftT
import arrow.check.property.log.monoid.monoid
import arrow.core.Either
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

@extension
interface TestTMonadThrow<M> : MonadThrow<TestTPartialOf<M>>, TestTMonadError<M, Throwable> {
    override fun ME(): MonadError<M, Throwable>
}

/*
TODO Why is WriterT.bracket fixed to Throwable?
@extension
interface TestTBracket<M, E> : Bracket<TestTPartialOf<M>, E>, TestTMonadError<M, E> {
    override fun ME(): MonadError<M, E> = MB()
    fun MB(): Bracket<M, E>
}
 */

@extension
interface TestTMonadDefer<M> : MonadDefer<TestTPartialOf<M>>, TestTMonadThrow<M> {
    override fun ME(): MonadError<M, Throwable> = MD()
    fun MD(): MonadDefer<M>

    override fun <A, B> Kind<TestTPartialOf<M>, A>.bracketCase(
        release: (A, ExitCase<Throwable>) -> Kind<TestTPartialOf<M>, Unit>,
        use: (A) -> Kind<TestTPartialOf<M>, B>
    ): Kind<TestTPartialOf<M>, B> = TestT(
        EitherT.monadDefer<WriterTPartialOf<M, Log>, Failure>(WriterT.monadDefer(MD(), Log.monoid())).run {
            fix().runTestT.bracketCase(release.andThen { it.fix().runTestT }, use.andThen { it.fix().runTestT })
        }
    )

    override fun <A> defer(fa: () -> Kind<TestTPartialOf<M>, A>): Kind<TestTPartialOf<M>, A> =
        TestT(EitherT(WriterT.monadDefer(MD(), Log.monoid()).defer(fa.andThen { it.fix().runTestT.value() })))
}

@extension
interface TestTAsync<M> : Async<TestTPartialOf<M>>, TestTMonadDefer<M> {
    override fun MD(): MonadDefer<M> = MA()
    fun MA(): Async<M>

    private fun eAsync() = EitherT.async<WriterTPartialOf<M, Log>, Failure>(WriterT.async(MA(), Log.monoid()))

    override fun <A> asyncF(k: ProcF<TestTPartialOf<M>, A>): Kind<TestTPartialOf<M>, A> =
        TestT(
            eAsync().asyncF(k.andThen { it.fix().runTestT.unit(WriterT.functor<M, Log>(MA())) })
        )

    override fun <A> Kind<TestTPartialOf<M>, A>.continueOn(ctx: CoroutineContext): Kind<TestTPartialOf<M>, A> =
        TestT(eAsync().run { fix().runTestT.continueOn(ctx) })
}

/* TODO Kapt out of memory error on kapt
@extension
interface TestTConcurrent<M> : Concurrent<TestTPartialOf<M>>, TestTAsync<M> {
    override fun MA(): Async<M> = MC()
    fun MC(): Concurrent<M>
    private fun eConc() = EitherT.concurrent<WriterTPartialOf<M, Log>, Failure>(WriterT.concurrent(MC(), Log.monoid()))

    override fun dispatchers(): Dispatchers<TestTPartialOf<M>> = eConc().dispatchers() as Dispatchers<TestTPartialOf<M>>

    override fun <A> Kind<TestTPartialOf<M>, A>.fork(ctx: CoroutineContext): Kind<TestTPartialOf<M>, Fiber<TestTPartialOf<M>, A>> =
        TestT(eConc().run {
            fix().runTestT.fork(ctx).fix().map { it.fiberT() }.fix()
        })

    // TODO arrow pr to add a functor instance to RacePair and RaceTriple
    override fun <A, B> CoroutineContext.racePair(
        fa: Kind<TestTPartialOf<M>, A>,
        fb: Kind<TestTPartialOf<M>, B>
    ): Kind<TestTPartialOf<M>, RacePair<TestTPartialOf<M>, A, B>> = TestT(
        eConc().run {
            racePair(fa.fix().runTestT, fb.fix().runTestT).map {
                it.fold({ a, f -> RacePair.First(a, f.fiberT())
                }, { f, b -> RacePair.Second(f.fiberT(), b) })
            }.fix()
        }
    )

    override fun <A, B, C> CoroutineContext.raceTriple(
        fa: Kind<TestTPartialOf<M>, A>,
        fb: Kind<TestTPartialOf<M>, B>,
        fc: Kind<TestTPartialOf<M>, C>
    ): Kind<TestTPartialOf<M>, RaceTriple<TestTPartialOf<M>, A, B, C>> = TestT(
        eConc().run {
            raceTriple(fa.fix().runTestT, fb.fix().runTestT, fc.fix().runTestT).map {
                it.fold({ a, f1, f2 ->
                    RaceTriple.First(a, f1.fiberT(), f2.fiberT())
                }, { f1, b, f2 ->
                    RaceTriple.Second(f1.fiberT(), b, f2.fiberT())
                }, { f1, f2, c ->
                    RaceTriple.Third(f1.fiberT(), f2.fiberT(), c)
                })
            }.fix()
        }
    )

    fun <A> Fiber<EitherTPartialOf<WriterTPartialOf<M, Log>, Failure>, A>.fiberT(): Fiber<TestTPartialOf<M>, A> =
        Fiber(TestT(join().fix()), TestT(cancel().fix()))
}
 */

/* TODO arrow pr
@extension
interface TestTMonadReader<M, D> : MonadReader<TestTPartialOf<M>, D>, TestTMonad<M> {
    override fun MM(): Monad<M> = MR()
    fun MR(): MonadReader<M, D>

    private fun eReader() = EitherT.monadRader()
}
 */
