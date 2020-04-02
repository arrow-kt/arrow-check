package arrow.check.property.instances

import arrow.Kind
import arrow.Kind2
import arrow.check.gen.GenT
import arrow.check.gen.instances.alternative
import arrow.check.gen.instances.monad
import arrow.check.gen.instances.monadError
import arrow.check.gen.instances.monadTrans
import arrow.check.property.*
import arrow.core.Either
import arrow.fx.IO
import arrow.fx.typeclasses.MonadIO
import arrow.mtl.typeclasses.MonadTrans
import arrow.typeclasses.*

// @extension
interface PropertyTFunctor<M> : Functor<PropertyTPartialOf<M>> {
    fun MM(): Monad<M>

    override fun <A, B> Kind<PropertyTPartialOf<M>, A>.map(f: (A) -> B): Kind<PropertyTPartialOf<M>, B> =
        fix().map(MM(), f)
}

fun <M> PropertyT.Companion.functor(MM: Monad<M>): Functor<PropertyTPartialOf<M>> = object : PropertyTFunctor<M> {
    override fun MM(): Monad<M> = MM
}

// @extension
interface PropertyTApplicative<M> : Applicative<PropertyTPartialOf<M>> {
    fun MM(): Monad<M>

    override fun <A> just(a: A): Kind<PropertyTPartialOf<M>, A> = PropertyT(TestT.just(GenT.monad(MM()), a))

    override fun <A, B> Kind<PropertyTPartialOf<M>, A>.ap(ff: Kind<PropertyTPartialOf<M>, (A) -> B>): Kind<PropertyTPartialOf<M>, B> =
        fix().ap(MM(), ff.fix())
}

fun <M> PropertyT.Companion.applicative(MM: Monad<M>): Applicative<PropertyTPartialOf<M>> = object : PropertyTApplicative<M> {
    override fun MM(): Monad<M> = MM
}

// @extension
interface PropertyTMonad<M> : Monad<PropertyTPartialOf<M>> {
    fun MM(): Monad<M>

    override fun <A> just(a: A): Kind<PropertyTPartialOf<M>, A> =
        PropertyT(TestT.monad(GenT.monad(MM())).just(a).fix())

    override fun <A, B> Kind<PropertyTPartialOf<M>, A>.flatMap(f: (A) -> Kind<PropertyTPartialOf<M>, B>): Kind<PropertyTPartialOf<M>, B> =
        TestT.monad(GenT.monad(MM())).run {
            PropertyT(
                fix().unPropertyT.flatMap { a ->
                    f(a).fix().unPropertyT
                }.fix()
            )
        }

    override fun <A, B> tailRecM(
        a: A, f: (A) -> Kind<PropertyTPartialOf<M>, Either<A, B>>
    ): Kind<PropertyTPartialOf<M>, B> =
        f(a).flatMap { it.fold({ tailRecM(it, f) }, { just(it) }) }
}

fun <M> PropertyT.Companion.monad(MM: Monad<M>): Monad<PropertyTPartialOf<M>> = object : PropertyTMonad<M> {
    override fun MM(): Monad<M> = MM
}

// @extension
interface PropertyTAlternative<M> : Alternative<PropertyTPartialOf<M>>, PropertyTApplicative<M> {
    override fun MM(): Monad<M>

    override fun <A> empty(): Kind<PropertyTPartialOf<M>, A> = discard(MM())

    override fun <A> Kind<PropertyTPartialOf<M>, A>.orElse(b: Kind<PropertyTPartialOf<M>, A>): Kind<PropertyTPartialOf<M>, A> =
        PropertyT(TestT.alternative(GenT.monad(MM()), GenT.alternative(MM())).run { fix().unPropertyT.orElse(b.fix().unPropertyT).fix() })
}

fun <M> PropertyT.Companion.alternative(MM: Monad<M>): Alternative<PropertyTPartialOf<M>> = object : PropertyTAlternative<M> {
    override fun MM(): Monad<M> = MM
}

interface PropertyTMonadTest<M> : MonadTest<PropertyTPartialOf<M>>, PropertyTMonad<M> {
    override fun MM(): Monad<M>

    override fun <A> Test<A>.liftTest(): Kind<PropertyTPartialOf<M>, A> =
        PropertyT(hoist(GenT.monad(MM())))
}

fun <M> PropertyT.Companion.monadTest(MM: Monad<M>): MonadTest<PropertyTPartialOf<M>> = object : PropertyTMonadTest<M> {
    override fun MM(): Monad<M> = MM
}

// @extension
interface PropertyTMonadTrans : MonadTrans<ForPropertyT> {
    override fun <G, A> Kind<G, A>.liftT(MF: Monad<G>): Kind2<ForPropertyT, G, A> =
        GenT.monadTrans().run { liftT(MF) }.let {
            PropertyT(TestT.monadTrans().run { it.liftT(GenT.monad(MF)).fix() })
        }
}

fun PropertyT.Companion.monadTrans(): MonadTrans<ForPropertyT> = object : PropertyTMonadTrans {}

// @extension
interface PropertyTMonadIO<M> : MonadIO<PropertyTPartialOf<M>>, PropertyTMonad<M> {
    override fun MM(): Monad<M> = MIO()
    fun MIO(): MonadIO<M>
    override fun <A> IO<Nothing, A>.liftIO(): Kind<PropertyTPartialOf<M>, A> = MIO().run {
        PropertyT.monadTrans().run {
            liftIO().liftT(MIO())
        }
    }
}

fun <M> PropertyT.Companion.monadIO(MIO: MonadIO<M>): MonadIO<PropertyTPartialOf<M>> = object : PropertyTMonadIO<M> {
    override fun MIO(): MonadIO<M> = MIO
}

// @extension
interface PropertyTApplicativeError<M, E> : ApplicativeError<PropertyTPartialOf<M>, E>, PropertyTApplicative<M> {
    override fun MM(): Monad<M> = ME()
    fun ME(): MonadError<M, E>
    override fun <A> Kind<PropertyTPartialOf<M>, A>.handleErrorWith(f: (E) -> Kind<PropertyTPartialOf<M>, A>): Kind<PropertyTPartialOf<M>, A> =
        TestT.monadError(GenT.monadError(ME())).run {
            PropertyT(fix().unPropertyT.handleErrorWith { f(it).fix().unPropertyT }.fix())
        }
    override fun <A> raiseError(e: E): Kind<PropertyTPartialOf<M>, A> =
        PropertyT(TestT.monadError(GenT.monadError(ME())).raiseError<A>(e).fix())
}

fun <M, E> PropertyT.Companion.applicativeError(ME: MonadError<M, E>): ApplicativeError<PropertyTPartialOf<M>, E> = object : PropertyTApplicativeError<M, E> {
    override fun ME(): MonadError<M, E> = ME
}

// @extension
interface PropertyTMonadError<M, E> : MonadError<PropertyTPartialOf<M>, E>, PropertyTApplicativeError<M, E>, PropertyTMonad<M> {
    override fun MM(): Monad<M> = ME()
    override fun ME(): MonadError<M, E>
    override fun <A> just(a: A): Kind<PropertyTPartialOf<M>, A> = PropertyT.monad(MM()).just(a)
    override fun <A, B> Kind<PropertyTPartialOf<M>, A>.ap(ff: Kind<PropertyTPartialOf<M>, (A) -> B>): Kind<PropertyTPartialOf<M>, B> =
        fix().ap(MM(), ff.fix())
}

fun <M, E> PropertyT.Companion.monadError(ME: MonadError<M, E>): MonadError<PropertyTPartialOf<M>, E> = object : PropertyTMonadError<M, E> {
    override fun ME(): MonadError<M, E> = ME
}

// Bracket when Rose has an instance
