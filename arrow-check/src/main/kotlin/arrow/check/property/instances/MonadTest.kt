package arrow.check.property.instances

import arrow.Kind
import arrow.check.property.MonadTest
import arrow.check.property.Test
import arrow.extension
import arrow.mtl.*
import arrow.mtl.extensions.*
import arrow.mtl.extensions.optiont.monadTrans.liftT
import arrow.typeclasses.Monad
import arrow.typeclasses.Monoid

@extension
interface OptionTMonadTest<M> : MonadTest<OptionTPartialOf<M>>, OptionTMonad<M> {
    override fun MF(): Monad<M> = MT()
    fun MT(): MonadTest<M>
    override fun <A> Test<A>.liftTest(): Kind<OptionTPartialOf<M>, A> = MT().run {
        liftTest().liftT(this)
    }
}

@extension
interface EitherTMonadTest<M, L> : MonadTest<EitherTPartialOf<M, L>>, EitherTMonad<M, L> {
    override fun MF(): Monad<M> = MT()
    fun MT(): MonadTest<M>
    override fun <A> Test<A>.liftTest(): Kind<EitherTPartialOf<M, L>, A> = MT().run {
        EitherT.liftF(this, liftTest())
    }
}

@extension
interface KleisliTMonadTest<M, D> : MonadTest<KleisliPartialOf<M, D>>, KleisliMonad<M, D> {
    override fun MF(): Monad<M> = MT()
    fun MT(): MonadTest<M>
    override fun <A> Test<A>.liftTest(): Kind<KleisliPartialOf<M, D>, A> = MT().run {
        Kleisli.liftF(liftTest())
    }
}

@extension
interface WriterTMonadTest<M, W> : MonadTest<WriterTPartialOf<M, W>>, WriterTMonad<M, W> {
    override fun MM(): Monoid<W>
    override fun MF(): Monad<M> = MT()
    fun MT(): MonadTest<M>

    override fun <A> Test<A>.liftTest(): Kind<WriterTPartialOf<M, W>, A> = MT().run {
        WriterT.liftF(liftTest(), MM(), MT())
    }
}

@extension
interface StateTMonadTest<M, S> : MonadTest<StateTPartialOf<M, S>>, StateTMonad<M, S> {
    override fun MF(): Monad<M> = MT()
    fun MT(): MonadTest<M>
    override fun <A> Test<A>.liftTest(): Kind<StateTPartialOf<M, S>, A> =
        MT().run {
            StateT.liftF(MT(), liftTest())
        }

}
