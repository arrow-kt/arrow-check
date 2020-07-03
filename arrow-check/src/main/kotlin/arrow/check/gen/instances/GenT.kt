package arrow.check.gen.instances

import arrow.Kind
import arrow.Kind2
import arrow.check.gen.ForGenT
import arrow.check.gen.GenT
import arrow.check.gen.GenTPartialOf
import arrow.check.gen.RandSeed
import arrow.check.gen.Rose
import arrow.check.gen.fix
import arrow.check.property.Size
import arrow.core.AndThen
import arrow.core.Either
import arrow.core.Tuple2
import arrow.core.andThen
import arrow.core.toT
import arrow.fx.IO
import arrow.fx.typeclasses.MonadIO
import arrow.mtl.OptionT
import arrow.mtl.extensions.optiont.alternative.alternative
import arrow.mtl.extensions.optiont.monad.monad
import arrow.mtl.extensions.optiont.monadError.monadError
import arrow.mtl.extensions.optiont.monadTrans.monadTrans
import arrow.mtl.typeclasses.MonadTrans
import arrow.typeclasses.Alternative
import arrow.typeclasses.Applicative
import arrow.typeclasses.ApplicativeError
import arrow.typeclasses.Functor
import arrow.typeclasses.Monad
import arrow.typeclasses.MonadError
import arrow.typeclasses.Monoid
import arrow.typeclasses.Semigroup

// @extension
interface GenTFunctor<M> : Functor<GenTPartialOf<M>> {
    fun FM(): Functor<M>

    override fun <A, B> Kind<GenTPartialOf<M>, A>.map(f: (A) -> B): Kind<GenTPartialOf<M>, B> =
        fix().genMap(FM(), f)
}

fun <M> GenT.Companion.functor(FM: Functor<M>): Functor<GenTPartialOf<M>> = object : GenTFunctor<M> {
    override fun FM(): Functor<M> = FM
}

// @extension
interface GenTApplicative<M> : Applicative<GenTPartialOf<M>> {
    fun MM(): Monad<M>

    override fun <A, B> Kind<GenTPartialOf<M>, A>.ap(ff: Kind<GenTPartialOf<M>, (A) -> B>): Kind<GenTPartialOf<M>, B> =
        fix().genAp(MM(), ff.fix())

    override fun <A> just(a: A): Kind<GenTPartialOf<M>, A> = GenT.just(MM(), a)
}

fun <M> GenT.Companion.applicative(MM: Monad<M>): Applicative<GenTPartialOf<M>> = object : GenTApplicative<M> {
    override fun MM(): Monad<M> = MM
}

/**
 * This violates monad laws because every flatMap splits the rng. In practice that is not a problem
 *  because the distribution of A's a Gen produces stays the same.
 * This can however be problematic when using the unsafe methods [promote], [delay].
 */
// @extension
interface GenTMonad<M> : Monad<GenTPartialOf<M>> {
    fun MM(): Monad<M>

    // explicit overwrite so I do not use the monadic version here
    override fun <A, B> Kind<GenTPartialOf<M>, A>.map(f: (A) -> B): Kind<GenTPartialOf<M>, B> =
        GenT.functor(MM()).run { map(f) }

    // explicit overwrite so I do not use the monadic version here
    override fun <A, B> Kind<GenTPartialOf<M>, A>.followedBy(fb: Kind<GenTPartialOf<M>, B>): Kind<GenTPartialOf<M>, B> =
        GenT.applicative(MM()).run { followedBy(fb) }

    // explicit overwrite so I do not use the monadic version here
    override fun <A, B> Kind<GenTPartialOf<M>, A>.apTap(fb: Kind<GenTPartialOf<M>, B>): Kind<GenTPartialOf<M>, A> =
        GenT.applicative(MM()).run { apTap(fb) }

    // explicit overwrite so I do not use the monadic version here
    override fun <A, B> Kind<GenTPartialOf<M>, A>.ap(ff: Kind<GenTPartialOf<M>, (A) -> B>): Kind<GenTPartialOf<M>, B> =
        fix().genAp(MM(), ff.fix())

    override fun <A, B> Kind<GenTPartialOf<M>, A>.flatMap(f: (A) -> Kind<GenTPartialOf<M>, B>): Kind<GenTPartialOf<M>, B> =
        fix().genFlatMap(MM(), f andThen { it.fix() })

    override fun <A> just(a: A): Kind<GenTPartialOf<M>, A> = GenT.just(MM(), a)

    override fun <A, B> tailRecM(a: A, f: (A) -> Kind<GenTPartialOf<M>, Either<A, B>>): Kind<GenTPartialOf<M>, B> =
        GenT { sizeAndSeed ->
            Rose.monad(OptionT.monad(MM())).tailRecM(a toT sizeAndSeed) { (a, sizeAndSeed) ->
                f(a).fix().runGWithSize(sizeAndSeed).let { (r, newSizeAndSeed) ->
                    r.map(OptionT.monad(MM())) { e -> e.mapLeft { it toT newSizeAndSeed } }
                }
            }.fix()
        }
}

fun <M> GenT.Companion.monad(MM: Monad<M>): Monad<GenTPartialOf<M>> = object : GenTMonad<M> {
    override fun MM(): Monad<M> = MM
}

// @extension
interface GenTAlternative<M> : Alternative<GenTPartialOf<M>>, GenTApplicative<M> {
    override fun MM(): Monad<M>
    override fun <A> empty(): Kind<GenTPartialOf<M>, A> =
        GenT { Rose.alternative(OptionT.alternative(MM()), OptionT.monad(MM())).empty<A>().fix() }

    override fun <A> Kind<GenTPartialOf<M>, A>.orElse(b: Kind<GenTPartialOf<M>, A>): Kind<GenTPartialOf<M>, A> =
        GenT(AndThen.id<Tuple2<RandSeed, Size>>().flatMap { t ->
            AndThen(fix().runGen).andThen { rose ->
                Rose.alternative(OptionT.alternative(MM()), OptionT.monad(MM())).run {
                    rose.lazyOrElse { (b.fix().runGen(t)) }.fix()
                }
            }
        })
}

fun <M> GenT.Companion.alternative(MM: Monad<M>): Alternative<GenTPartialOf<M>> = object : GenTAlternative<M> {
    override fun MM(): Monad<M> = MM
}

// @extension
interface GenTMonadTrans : MonadTrans<ForGenT> {
    override fun <G, A> Kind<G, A>.liftT(MF: Monad<G>): Kind2<ForGenT, G, A> = GenT {
        OptionT.monadTrans().run {
            liftT(MF)
        }.let { Rose.monadTrans().run { it.liftT(OptionT.monad(MF)).fix() } }
    }
}

fun GenT.Companion.monadTrans(): MonadTrans<ForGenT> = object : GenTMonadTrans {}

// @extension
interface GenTMonadIO<M> : MonadIO<GenTPartialOf<M>>, GenTMonad<M> {
    override fun MM(): Monad<M> = MIO()
    fun MIO(): MonadIO<M>
    override fun <A> IO<A>.liftIO(): Kind<GenTPartialOf<M>, A> = MIO().run {
        GenT.monadTrans().run {
            liftIO().liftT(MIO())
        }
    }
}

fun <M> GenT.Companion.monadIO(MIO: MonadIO<M>): MonadIO<GenTPartialOf<M>> = object : GenTMonadIO<M> {
    override fun MIO(): MonadIO<M> = MIO
}

// @extension
interface GenTApplicativeError<M, E> : ApplicativeError<GenTPartialOf<M>, E>, GenTApplicative<M> {
    override fun MM(): Monad<M> = ME()
    fun ME(): MonadError<M, E>

    override fun <A> Kind<GenTPartialOf<M>, A>.handleErrorWith(f: (E) -> Kind<GenTPartialOf<M>, A>): Kind<GenTPartialOf<M>, A> =
        GenT(AndThen.id<Tuple2<RandSeed, Size>>().flatMap { t ->
            AndThen(fix().runGen).andThen { rose ->
                Rose.applicativeError(OptionT.monadError(ME())).run {
                    rose.handleErrorWith { f(it).fix().runGen(t) }.fix()
                }
            }
        })

    override fun <A> raiseError(e: E): Kind<GenTPartialOf<M>, A> = GenT {
        Rose.monadError(OptionT.monadError(ME())).raiseError<A>(e).fix()
    }
}

fun <M, E> GenT.Companion.applicativeError(ME: MonadError<M, E>): ApplicativeError<GenTPartialOf<M>, E> =
    object : GenTApplicativeError<M, E> {
        override fun ME(): MonadError<M, E> = ME
    }

// @extension
interface GenTMonadError<M, E> : MonadError<GenTPartialOf<M>, E>, GenTApplicativeError<M, E>, GenTMonad<M> {
    override fun MM(): Monad<M> = ME()
    override fun ME(): MonadError<M, E>
    override fun <A, B> Kind<GenTPartialOf<M>, A>.ap(ff: Kind<GenTPartialOf<M>, (A) -> B>): Kind<GenTPartialOf<M>, B> =
        fix().genAp(ME(), ff.fix())

    override fun <A> just(a: A): Kind<GenTPartialOf<M>, A> = GenT.just(ME(), a)
}

fun <M, E> GenT.Companion.monadError(ME: MonadError<M, E>): MonadError<GenTPartialOf<M>, E> =
    object : GenTMonadError<M, E> {
        override fun ME(): MonadError<M, E> = ME
    }

// @extension
interface GenTSemigroup<M, A> : Semigroup<GenT<M, A>> {
    fun MM(): Monad<M>
    fun SA(): Semigroup<A>
    override fun GenT<M, A>.combine(b: GenT<M, A>): GenT<M, A> =
        GenT.applicative(MM()).mapN(this, b) { (a, b) -> SA().run { a + b } }.fix()
}

fun <M, A> GenT.Companion.semigroup(MM: Monad<M>, SA: Semigroup<A>): Semigroup<GenT<M, A>> =
    object : GenTSemigroup<M, A> {
        override fun MM(): Monad<M> = MM
        override fun SA(): Semigroup<A> = SA
    }

// @extension
interface GenTMonoid<M, A> : Monoid<GenT<M, A>>, GenTSemigroup<M, A> {
    override fun MM(): Monad<M>
    override fun SA(): Semigroup<A> = MA()
    fun MA(): Monoid<A>
    override fun empty(): GenT<M, A> = GenT.monadTrans().run { MM().just(MA().empty()).liftT(MM()).fix() }
}

fun <M, A> GenT.Companion.monoid(MM: Monad<M>, MA: Monoid<A>): Monoid<GenT<M, A>> = object : GenTMonoid<M, A> {
    override fun MA(): Monoid<A> = MA
    override fun MM(): Monad<M> = MM
}
