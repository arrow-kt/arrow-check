package arrow.check.gen.instances

import arrow.Kind
import arrow.Kind2
import arrow.check.gen.*
import arrow.check.gen.instances.gent.applicative.applicative
import arrow.check.gen.instances.gent.functor.functor
import arrow.check.gen.instances.gent.monadTrans.liftT
import arrow.check.gen.instances.gent.monadTrans.monadTrans
import arrow.check.gen.instances.rose.alternative.alternative
import arrow.check.gen.instances.rose.alternative.orElse
import arrow.check.gen.instances.rose.applicativeError.handleErrorWith
import arrow.check.gen.instances.rose.monadError.monadError
import arrow.check.gen.instances.rose.monadReader.local
import arrow.check.gen.instances.rose.monadReader.monadReader
import arrow.check.gen.instances.rose.monadTrans.monadTrans
import arrow.core.AndThen
import arrow.core.Either
import arrow.core.andThen
import arrow.extension
import arrow.fx.IO
import arrow.fx.typeclasses.MonadIO
import arrow.mtl.OptionT
import arrow.mtl.extensions.optiont.alternative.alternative
import arrow.mtl.extensions.optiont.monad.monad
import arrow.mtl.extensions.optiont.monadError.monadError
import arrow.mtl.extensions.optiont.monadTrans.monadTrans
import arrow.mtl.typeclasses.MonadReader
import arrow.mtl.typeclasses.MonadTrans
import arrow.typeclasses.*

@extension
interface GenTFunctor<M> : Functor<GenTPartialOf<M>> {
    fun FM(): Functor<M>

    override fun <A, B> Kind<GenTPartialOf<M>, A>.map(f: (A) -> B): Kind<GenTPartialOf<M>, B> =
        fix().genMap(FM(), f)
}

@extension
interface GenTApplicative<M> : Applicative<GenTPartialOf<M>> {
    fun MM(): Monad<M>

    override fun <A, B> Kind<GenTPartialOf<M>, A>.ap(ff: Kind<GenTPartialOf<M>, (A) -> B>): Kind<GenTPartialOf<M>, B> =
        fix().genAp(MM(), ff.fix())

    override fun <A> just(a: A): Kind<GenTPartialOf<M>, A> = GenT.just(MM(), a)
}

/**
 * This violates monad laws because every flatMap splits the rng. In practice that is not a problem
 *  because the distribution of A's a Gen produces stays the same.
 * This can however be problematic when using the unsafe methods [promote], [delay].
 */
@extension
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
    override fun <A, B> Kind<GenTPartialOf<M>, A>.lazyAp(ff: () -> Kind<GenTPartialOf<M>, (A) -> B>): Kind<GenTPartialOf<M>, B> =
        fix().genAp(MM(), ff().fix())

    // explicit overwrite so I do not use the monadic version here
    override fun <A, B> Kind<GenTPartialOf<M>, A>.ap(ff: Kind<GenTPartialOf<M>, (A) -> B>): Kind<GenTPartialOf<M>, B> =
        fix().genAp(MM(), ff.fix())

    override fun <A, B> Kind<GenTPartialOf<M>, A>.flatMap(f: (A) -> Kind<GenTPartialOf<M>, B>): Kind<GenTPartialOf<M>, B> =
        fix().genFlatMap(MM(), f andThen { it.fix() })

    override fun <A> just(a: A): Kind<GenTPartialOf<M>, A> = GenT.just(MM(), a)

    override fun <A, B> tailRecM(a: A, f: (A) -> Kind<GenTPartialOf<M>, Either<A, B>>): Kind<GenTPartialOf<M>, B> =
        f(a).flatMap {
            it.fold({
                tailRecM(it, f)
            }, {
                just(it)
            })
        }
}

@extension
interface GenTAlternative<M> : Alternative<GenTPartialOf<M>>, GenTApplicative<M> {
    override fun MM(): Monad<M>
    override fun <A> empty(): Kind<GenTPartialOf<M>, A> =
        GenT { Rose.alternative(OptionT.alternative(MM()), OptionT.monad(MM())).empty<A>().fix() }

    override fun <A> Kind<GenTPartialOf<M>, A>.orElse(b: Kind<GenTPartialOf<M>, A>): Kind<GenTPartialOf<M>, A> =
        GenT { t -> (fix().runGen(t).orElse(OptionT.alternative(MM()), OptionT.monad(MM()), b.fix().runGen(t))) }
}

@extension
interface GenTMonadTrans : MonadTrans<ForGenT> {
    override fun <G, A> Kind<G, A>.liftT(MF: Monad<G>): Kind2<ForGenT, G, A> = GenT {
        OptionT.monadTrans().run {
            liftT(MF)
        }.let { Rose.monadTrans().run { it.liftT(OptionT.monad(MF)).fix() } }
    }
}

@extension
interface GenTMonadIO<M> : MonadIO<GenTPartialOf<M>>, GenTMonad<M> {
    override fun MM(): Monad<M> = MIO()
    fun MIO(): MonadIO<M>
    override fun <A> IO<A>.liftIO(): Kind<GenTPartialOf<M>, A> = MIO().run {
        liftIO().liftT(this)
    }
}

@extension
interface GenTApplicativeError<M, E> : ApplicativeError<GenTPartialOf<M>, E>, GenTApplicative<M> {
    override fun MM(): Monad<M> = ME()
    fun ME(): MonadError<M, E>

    override fun <A> Kind<GenTPartialOf<M>, A>.handleErrorWith(f: (E) -> Kind<GenTPartialOf<M>, A>): Kind<GenTPartialOf<M>, A> =
        GenT { i ->
            fix().runGen(i).handleErrorWith(OptionT.monadError(ME())) { f(it).fix().runGen(i) }
        }

    override fun <A> raiseError(e: E): Kind<GenTPartialOf<M>, A> = GenT {
        Rose.monadError(OptionT.monadError(ME())).raiseError<A>(e).fix()
    }
}

@extension
interface GenTMonadError<M, E> : MonadError<GenTPartialOf<M>, E>, GenTApplicativeError<M, E>, GenTMonad<M> {
    override fun MM(): Monad<M> = ME()
    override fun ME(): MonadError<M, E>
    override fun <A, B> Kind<GenTPartialOf<M>, A>.ap(ff: Kind<GenTPartialOf<M>, (A) -> B>): Kind<GenTPartialOf<M>, B> =
        fix().genAp(ME(), ff.fix())
    override fun <A> just(a: A): Kind<GenTPartialOf<M>, A> = GenT.just(ME(), a)
}

@extension
interface GenTSemigroup<M, A> : Semigroup<GenT<M, A>> {
    fun MM(): Monad<M>
    fun SA(): Semigroup<A>
    override fun GenT<M, A>.combine(b: GenT<M, A>): GenT<M, A> = GenT.applicative(MM()).mapN(this, b) { (a, b) -> SA().run { a + b } }.fix()
}

@extension
interface GenTMonoid<M, A> : Monoid<GenT<M, A>>, GenTSemigroup<M, A> {
    override fun MM(): Monad<M>
    override fun SA(): Semigroup<A> = MA()
    fun MA(): Monoid<A>
    override fun empty(): GenT<M, A> = GenT.monadTrans().run { MM().just(MA().empty()).liftT(MM()).fix() }
}
