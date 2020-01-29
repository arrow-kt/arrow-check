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

fun <M, L> EitherT.Companion.monadTest(MT: MonadTest<M>): MonadTest<EitherTPartialOf<M, L>> = object : EitherTMonadTest<M, L> {
    override fun MT(): MonadTest<M> = MT
}

interface EitherTMonadTest<M, L> : MonadTest<EitherTPartialOf<M, L>>, EitherTMonad<M, L> {
    override fun MF(): Monad<M> = MT()
    fun MT(): MonadTest<M>
    override fun <A> Test<A>.liftTest(): Kind<EitherTPartialOf<M, L>, A> = MT().run {
        EitherT.liftF(this, liftTest())
    }
}

fun <M, D> Kleisli.Companion.monadTest(MT: MonadTest<M>): MonadTest<KleisliPartialOf<M, D>> = object : KleisliTMonadTest<M, D> {
    override fun MT(): MonadTest<M> = MT
}

interface KleisliTMonadTest<M, D> : MonadTest<KleisliPartialOf<M, D>>, KleisliMonad<M, D> {
    override fun MF(): Monad<M> = MT()
    fun MT(): MonadTest<M>
    override fun <A> Test<A>.liftTest(): Kind<KleisliPartialOf<M, D>, A> = MT().run {
        Kleisli.liftF(liftTest())
    }
}

fun <M, W> WriterT.Companion.monadTest(MT: MonadTest<M>, MM: Monoid<W>): MonadTest<WriterTPartialOf<M, W>> = object : WriterTMonadTest<M, W> {
    override fun MT(): MonadTest<M> = MT
    override fun MM(): Monoid<W> = MM
}

interface WriterTMonadTest<M, W> : MonadTest<WriterTPartialOf<M, W>>, WriterTMonad<M, W> {
    override fun MM(): Monoid<W>
    override fun MF(): Monad<M> = MT()
    fun MT(): MonadTest<M>

    override fun <A> Test<A>.liftTest(): Kind<WriterTPartialOf<M, W>, A> = MT().run {
        WriterT.liftF(liftTest(), MM(), MT())
    }
}

fun <M, S> StateT.Companion.monadTest(MT: MonadTest<M>): MonadTest<StateTPartialOf<M, S>> = object : StateTMonadTest<M, S> {
    override fun MT(): MonadTest<M> = MT
}

interface StateTMonadTest<M, S> : MonadTest<StateTPartialOf<M, S>>, StateTMonad<M, S> {
    override fun MF(): Monad<M> = MT()
    fun MT(): MonadTest<M>
    override fun <A> Test<A>.liftTest(): Kind<StateTPartialOf<M, S>, A> =
        MT().run {
            StateT.liftF(MT(), liftTest())
        }

}
