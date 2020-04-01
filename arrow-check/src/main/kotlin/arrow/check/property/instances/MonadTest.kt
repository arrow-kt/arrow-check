package arrow.check.property.instances

import arrow.Kind
import arrow.check.property.MonadTest
import arrow.check.property.Test
import arrow.mtl.*
import arrow.mtl.extensions.*
import arrow.mtl.extensions.optiont.monadTrans.liftT
import arrow.typeclasses.Monad
import arrow.typeclasses.Monoid

fun <M> OptionT.Companion.monadTest(MT: MonadTest<M>): MonadTest<OptionTPartialOf<M>> = object : OptionTMonadTest<M> {
    override fun MT(): MonadTest<M> = MT
}

interface OptionTMonadTest<M> : MonadTest<OptionTPartialOf<M>>, OptionTMonad<M> {
    override fun MF(): Monad<M> = MT()
    fun MT(): MonadTest<M>
    override fun <A> Test<A>.liftTest(): Kind<OptionTPartialOf<M>, A> = MT().run {
        liftTest().liftT(this)
    }
}

fun <L, M> EitherT.Companion.monadTest(MT: MonadTest<M>): MonadTest<EitherTPartialOf<L, M>> = object : EitherTMonadTest<L, M> {
    override fun MT(): MonadTest<M> = MT
}

interface EitherTMonadTest<L, M> : MonadTest<EitherTPartialOf<L, M>>, EitherTMonad<L, M> {
    override fun MF(): Monad<M> = MT()
    fun MT(): MonadTest<M>
    override fun <A> Test<A>.liftTest(): Kind<EitherTPartialOf<L, M>, A> = MT().run {
        EitherT.liftF(this, liftTest())
    }
}

fun <D, M> Kleisli.Companion.monadTest(MT: MonadTest<M>): MonadTest<KleisliPartialOf<D, M>> = object : KleisliTMonadTest<D, M> {
    override fun MT(): MonadTest<M> = MT
}

interface KleisliTMonadTest<D, M> : MonadTest<KleisliPartialOf<D, M>>, KleisliMonad<D, M> {
    override fun MF(): Monad<M> = MT()
    fun MT(): MonadTest<M>
    override fun <A> Test<A>.liftTest(): Kind<KleisliPartialOf<D, M>, A> = MT().run {
        Kleisli.liftF(liftTest())
    }
}

fun <W, M> WriterT.Companion.monadTest(MT: MonadTest<M>, MM: Monoid<W>): MonadTest<WriterTPartialOf<W, M>> = object : WriterTMonadTest<W, M> {
    override fun MT(): MonadTest<M> = MT
    override fun MM(): Monoid<W> = MM
}

interface WriterTMonadTest<W, M> : MonadTest<WriterTPartialOf<W, M>>, WriterTMonad<W, M> {
    override fun MM(): Monoid<W>
    override fun MF(): Monad<M> = MT()
    fun MT(): MonadTest<M>

    override fun <A> Test<A>.liftTest(): Kind<WriterTPartialOf<W, M>, A> = MT().run {
        WriterT.liftF(liftTest(), MM(), MT())
    }
}

fun <S, M> StateT.Companion.monadTest(MT: MonadTest<M>): MonadTest<StateTPartialOf<S, M>> = object : StateTMonadTest<S, M> {
    override fun MT(): MonadTest<M> = MT
}

interface StateTMonadTest<S, M> : MonadTest<StateTPartialOf<S, M>>, StateTMonad<S, M> {
    override fun MF(): Monad<M> = MT()
    fun MT(): MonadTest<M>
    override fun <A> Test<A>.liftTest(): Kind<StateTPartialOf<S, M>, A> =
        MT().run {
            StateT.liftF(MT(), liftTest())
        }

}
