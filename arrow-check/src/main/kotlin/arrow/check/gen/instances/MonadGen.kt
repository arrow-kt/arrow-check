package arrow.check.gen.instances

import arrow.Kind
import arrow.Kind2
import arrow.check.gen.ForGenT
import arrow.check.gen.ForRose
import arrow.check.gen.GenT
import arrow.check.gen.GenTPartialOf
import arrow.check.gen.MonadGen
import arrow.check.gen.Rose
import arrow.check.gen.RoseF
import arrow.check.gen.RosePartialOf
import arrow.check.gen.fix
import arrow.core.AndThen
import arrow.core.Either
import arrow.core.FunctionK
import arrow.mtl.EitherT
import arrow.mtl.EitherTPartialOf
import arrow.mtl.ForEitherT
import arrow.mtl.ForKleisli
import arrow.mtl.ForOptionT
import arrow.mtl.ForStateT
import arrow.mtl.ForWriterT
import arrow.mtl.Kleisli
import arrow.mtl.KleisliPartialOf
import arrow.mtl.OptionT
import arrow.mtl.OptionTPartialOf
import arrow.mtl.StateT
import arrow.mtl.StateTPartialOf
import arrow.mtl.WriterT
import arrow.mtl.WriterTPartialOf
import arrow.mtl.extensions.EitherTMonad
import arrow.mtl.extensions.KleisliMonad
import arrow.mtl.extensions.OptionTMonad
import arrow.mtl.extensions.StateTMonad
import arrow.mtl.extensions.eithert.monad.monad
import arrow.mtl.extensions.eithert.monadTrans.monadTrans
import arrow.mtl.extensions.kleisli.monad.monad
import arrow.mtl.extensions.kleisli.monadTrans.monadTrans
import arrow.mtl.extensions.optiont.monad.monad
import arrow.mtl.extensions.optiont.monadTrans.monadTrans
import arrow.mtl.extensions.statet.monad.monad
import arrow.mtl.extensions.statet.monadTrans.monadTrans
import arrow.mtl.extensions.writert.monad.monad
import arrow.mtl.extensions.writert.monadTrans.monadTrans
import arrow.mtl.fix
import arrow.mtl.typeclasses.MonadTrans
import arrow.typeclasses.Monad
import arrow.typeclasses.Monoid

// Pray for arrow meta to do those instance lookups in the future // this is 4-5 lines per instance in haskell^^

/**
 * Try doing this for a monad without all these helpers^^ As verbose as it is, this is actually quite the nice solution.
 */
fun <M, B> OptionT.Companion.monadGen(MG: MonadGen<M, B>): MonadGen<OptionTPartialOf<M>, OptionTPartialOf<B>> =
    object : OptionTMonadGen<M, B> {
        override fun MG(): MonadGen<M, B> = MG
    }

interface OptionTMonadGen<M, B> : MonadGen<OptionTPartialOf<M>, OptionTPartialOf<B>>, OptionTMonad<M> {
    fun MG(): MonadGen<M, B>

    override fun MF(): Monad<M> = MG().MM()
    override fun BM(): Monad<OptionTPartialOf<B>> = OptionT.monad(MG().BM())
    override fun MM(): Monad<OptionTPartialOf<M>> = OptionT.monad(MG().MM())

    override fun <A> GenT<OptionTPartialOf<B>, A>.fromGenT(): Kind<OptionTPartialOf<M>, A> =
        GenT.monadTransDistributive().run {
            distributeT(
                OptionT.monadTrans(),
                OptionT.mFunctor(),
                MG().BM(),
                object : MonadTransDistributive.MonadFromMonadTrans<ForOptionT> {
                    override fun <M> monad(MM: Monad<M>): Monad<Kind<ForOptionT, M>> = OptionT.monad(MM)
                }).fix().let {
                OptionT.mFunctor().run {
                    it.hoist(GenT.monad(MG().BM()), object : FunctionK<GenTPartialOf<B>, M> {
                        override fun <A> invoke(fa: Kind<GenTPartialOf<B>, A>): Kind<M, A> =
                            MG().run { fa.fix().fromGenT() }
                    })
                }
            }
        }

    override fun <A> Kind<OptionTPartialOf<M>, A>.toGenT(): GenT<OptionTPartialOf<B>, A> = OptionT.mFunctor().run {
        hoist(MF(), object : FunctionK<M, GenTPartialOf<B>> {
            override fun <A> invoke(fa: Kind<M, A>): Kind<GenTPartialOf<B>, A> = MG().run { fa.toGenT() }
        }).let {
            OptionT.monadTransDistributive().run {
                it.distributeT(
                    GenT.monadTrans(),
                    GenT.mFunctor(),
                    MG().BM(),
                    object : MonadTransDistributive.MonadFromMonadTrans<ForGenT> {
                        override fun <M> monad(MM: Monad<M>): Monad<Kind<ForGenT, M>> = GenT.monad(MM)
                    }).fix()
            }
        }
    }
}

fun <L, M, B> EitherT.Companion.monadGen(MG: MonadGen<M, B>): MonadGen<EitherTPartialOf<L, M>, EitherTPartialOf<L, B>> =
    object : EitherTMonadGen<L, M, B> {
        override fun MG(): MonadGen<M, B> = MG
    }

interface EitherTMonadGen<L, M, B> : MonadGen<EitherTPartialOf<L, M>, EitherTPartialOf<L, B>>, EitherTMonad<L, M> {
    fun MG(): MonadGen<M, B>

    override fun MF(): Monad<M> = MG().MM()
    override fun BM(): Monad<EitherTPartialOf<L, B>> = EitherT.monad(MG().BM())
    override fun MM(): Monad<EitherTPartialOf<L, M>> = EitherT.monad(MG().MM())

    override fun <A> GenT<EitherTPartialOf<L, B>, A>.fromGenT(): Kind<EitherTPartialOf<L, M>, A> =
        GenT.monadTransDistributive().run {
            distributeT(
                EitherT.monadTrans(),
                EitherT.mFunctor(),
                MG().BM(),
                object : MonadTransDistributive.MonadFromMonadTrans<Kind<ForEitherT, L>> {
                    override fun <M> monad(MM: Monad<M>): Monad<Kind<Kind<ForEitherT, L>, M>> = EitherT.monad(MM)
                }
            ).fix().let {
                EitherT.mFunctor<L>().run {
                    it.hoist(GenT.monad(MG().BM()), object : FunctionK<GenTPartialOf<B>, M> {
                        override fun <A> invoke(fa: Kind<GenTPartialOf<B>, A>): Kind<M, A> =
                            MG().run { fa.fix().fromGenT() }
                    })
                }
            }
        }

    override fun <A> Kind<EitherTPartialOf<L, M>, A>.toGenT(): GenT<EitherTPartialOf<L, B>, A> = EitherT.mFunctor<L>().run {
        hoist(MF(), object : FunctionK<M, GenTPartialOf<B>> {
            override fun <A> invoke(fa: Kind<M, A>): Kind<GenTPartialOf<B>, A> = MG().run { fa.toGenT() }
        }).let {
            EitherT.monadTransDistributive<L>().run {
                it.distributeT(
                    GenT.monadTrans(),
                    GenT.mFunctor(),
                    MG().BM(),
                    object : MonadTransDistributive.MonadFromMonadTrans<ForGenT> {
                        override fun <M> monad(MM: Monad<M>): Monad<Kind<ForGenT, M>> = GenT.monad(MM)
                    }
                ).fix()
            }
        }
    }
}

fun <W, M, B> WriterT.Companion.monadGen(MG: MonadGen<M, B>, MW: Monoid<W>): MonadGen<WriterTPartialOf<W, M>, WriterTPartialOf<W, B>> =
    object : WriterTMonadGen<W, M, B> {
        override fun MG(): MonadGen<M, B> = MG
        override fun MW(): Monoid<W> = MW
    }

interface WriterTMonadGen<W, M, B> : MonadGen<WriterTPartialOf<W, M>, WriterTPartialOf<W, B>> {
    fun MG(): MonadGen<M, B>
    fun MW(): Monoid<W>

    override fun BM(): Monad<WriterTPartialOf<W, B>> = WriterT.monad(MG().BM(), MW())
    override fun MM(): Monad<WriterTPartialOf<W, M>> = WriterT.monad(MG().MM(), MW())

    override fun <A> GenT<WriterTPartialOf<W, B>, A>.fromGenT(): Kind<WriterTPartialOf<W, M>, A> =
        GenT.monadTransDistributive().run {
            distributeT(
                WriterT.monadTrans(MW()),
                WriterT.mFunctor(),
                MG().BM(),
                object : MonadTransDistributive.MonadFromMonadTrans<Kind<ForWriterT, W>> {
                    override fun <M> monad(MM: Monad<M>): Monad<Kind<Kind<ForWriterT, W>, M>> = WriterT.monad(MM, MW())
                }
            ).fix().let {
                WriterT.mFunctor<W>().run {
                    it.hoist(GenT.monad(MG().BM()), object : FunctionK<GenTPartialOf<B>, M> {
                        override fun <A> invoke(fa: Kind<GenTPartialOf<B>, A>): Kind<M, A> =
                            MG().run { fa.fix().fromGenT() }
                    })
                }
            }
        }

    override fun <A> Kind<WriterTPartialOf<W, M>, A>.toGenT(): GenT<WriterTPartialOf<W, B>, A> =
        WriterT.mFunctor<W>().run {
            hoist(MG().MM(), object : FunctionK<M, GenTPartialOf<B>> {
                override fun <A> invoke(fa: Kind<M, A>): Kind<GenTPartialOf<B>, A> = MG().run { fa.toGenT() }
            })
        }.let {
            WriterT.monadTransDistributive(MW()).run {
                it.distributeT(
                    GenT.monadTrans(),
                    GenT.mFunctor(),
                    MG().BM(),
                    object : MonadTransDistributive.MonadFromMonadTrans<ForGenT> {
                        override fun <M> monad(MM: Monad<M>): Monad<Kind<ForGenT, M>> = GenT.monad(MM)
                    }
                ).fix()
            }
        }

    override fun <A, B> Kind<WriterTPartialOf<W, M>, A>.flatMap(f: (A) -> Kind<WriterTPartialOf<W, M>, B>): Kind<WriterTPartialOf<W, M>, B> =
        fix().flatMap(MG().MM(), MW(), f)

    override fun <A> just(a: A): Kind<WriterTPartialOf<W, M>, A> = WriterT.just(MG().MM(), MW(), a)

    override fun <A, B> tailRecM(
        a: A,
        f: (A) -> Kind<WriterTPartialOf<W, M>, Either<A, B>>
    ): Kind<WriterTPartialOf<W, M>, B> = WriterT.tailRecM(MG().MM(), a, f)
}

fun <D, M, B> Kleisli.Companion.monadGen(MG: MonadGen<M, B>): MonadGen<KleisliPartialOf<D, M>, KleisliPartialOf<D, B>> =
    object : KleisliMonadGen<D, M, B> {
        override fun MG(): MonadGen<M, B> = MG
    }

interface KleisliMonadGen<D, M, B> : MonadGen<KleisliPartialOf<D, M>, KleisliPartialOf<D, B>>, KleisliMonad<D, M> {
    fun MG(): MonadGen<M, B>

    override fun MF(): Monad<M> = MG().MM()
    override fun BM(): Monad<KleisliPartialOf<D, B>> = Kleisli.monad(MG().BM())
    override fun MM(): Monad<KleisliPartialOf<D, M>> = Kleisli.monad(MG().MM())

    override fun <A> GenT<KleisliPartialOf<D, B>, A>.fromGenT(): Kind<KleisliPartialOf<D, M>, A> =
        GenT.monadTransDistributive().run {
            distributeT(
                Kleisli.monadTrans(),
                Kleisli.mFunctor(),
                MG().BM(),
                object : MonadTransDistributive.MonadFromMonadTrans<Kind<ForKleisli, D>> {
                    override fun <M> monad(MM: Monad<M>): Monad<Kind<Kind<ForKleisli, D>, M>> = Kleisli.monad(MM)
                }
            ).let {
                Kleisli.mFunctor<D>().run {
                    it.hoist(GenT.monad(MG().BM()), object : FunctionK<GenTPartialOf<B>, M> {
                        override fun <A> invoke(fa: Kind<GenTPartialOf<B>, A>): Kind<M, A> =
                            MG().run { fa.fix().fromGenT() }
                    })
                }
            }
        }

    override fun <A> Kind<KleisliPartialOf<D, M>, A>.toGenT(): GenT<KleisliPartialOf<D, B>, A> =
        Kleisli.mFunctor<D>().run {
            hoist(MG().MM(), object : FunctionK<M, GenTPartialOf<B>> {
                override fun <A> invoke(fa: Kind<M, A>): Kind<GenTPartialOf<B>, A> = MG().run { fa.toGenT() }
            })
        }.let {
            Kleisli.monadTransDistributive<D>().run {
                it.distributeT(
                    GenT.monadTrans(),
                    GenT.mFunctor(),
                    MG().BM(),
                    object : MonadTransDistributive.MonadFromMonadTrans<ForGenT> {
                        override fun <M> monad(MM: Monad<M>): Monad<Kind<ForGenT, M>> = GenT.monad(MM)
                    }
                ).fix()
            }
        }
}

fun <S, M, B> StateT.Companion.monadBase(MG: MonadGen<M, B>): MonadGen<StateTPartialOf<S, M>, StateTPartialOf<S, B>> =
    object : StateTMonadGen<S, M, B> {
        override fun MG(): MonadGen<M, B> = MG
    }

interface StateTMonadGen<S, M, B> : MonadGen<StateTPartialOf<S, M>, StateTPartialOf<S, B>>, StateTMonad<S, M> {
    fun MG(): MonadGen<M, B>

    override fun MF(): Monad<M> = MG().MM()
    override fun BM(): Monad<StateTPartialOf<S, B>> = StateT.monad(MG().BM())
    override fun MM(): Monad<StateTPartialOf<S, M>> = StateT.monad(MG().MM())

    override fun <A> GenT<StateTPartialOf<S, B>, A>.fromGenT(): Kind<StateTPartialOf<S, M>, A> =
        GenT.monadTransDistributive().run {
            distributeT(
                StateT.monadTrans(),
                StateT.mFunctor(),
                MG().BM(),
                object : MonadTransDistributive.MonadFromMonadTrans<Kind<ForStateT, S>> {
                    override fun <M> monad(MM: Monad<M>): Monad<Kind<Kind<ForStateT, S>, M>> = StateT.monad(MM)
                }
            ).let {
                StateT.mFunctor<S>().run {
                    it.hoist(GenT.monad(MG().BM()), object : FunctionK<GenTPartialOf<B>, M> {
                        override fun <A> invoke(fa: Kind<GenTPartialOf<B>, A>): Kind<M, A> =
                            MG().run { fa.fix().fromGenT() }
                    })
                }
            }
        }

    override fun <A> Kind<StateTPartialOf<S, M>, A>.toGenT(): GenT<StateTPartialOf<S, B>, A> =
        StateT.mFunctor<S>().run {
            hoist(MG().MM(), object : FunctionK<M, GenTPartialOf<B>> {
                override fun <A> invoke(fa: Kind<M, A>): Kind<GenTPartialOf<B>, A> = MG().run { fa.toGenT() }
            })
        }.let {
            StateT.monadTransDistributive<S>().run {
                it.distributeT(
                    GenT.monadTrans(),
                    GenT.mFunctor(),
                    MG().BM(),
                    object : MonadTransDistributive.MonadFromMonadTrans<ForGenT> {
                        override fun <M> monad(MM: Monad<M>): Monad<Kind<ForGenT, M>> = GenT.monad(MM)
                    }
                ).fix()
            }
        }
}

// --------------------------- Utilities for implementing MonadGen which is by no means trivial
/**
 * Map over the inner monad rather than the contained type
 *
 * TODO This should probably be in another library...
 */
interface MFunctor<F> {
    fun <M, N, A> Kind2<F, M, A>.hoist(MM: Monad<M>, f: FunctionK<M, N>): Kind2<F, N, A>
}

// @extension
interface OptionTMFunctor : MFunctor<ForOptionT> {
    override fun <M, N, A> Kind2<ForOptionT, M, A>.hoist(MM: Monad<M>, f: FunctionK<M, N>): Kind2<ForOptionT, N, A> =
        OptionT(f(fix().value()))
}

fun OptionT.Companion.mFunctor(): MFunctor<ForOptionT> = object : OptionTMFunctor {}

// @extension
interface KleisliMFunctor<D> : MFunctor<Kind<ForKleisli, D>> {
    override fun <M, N, A> Kind2<Kind<ForKleisli, D>, M, A>.hoist(
        MM: Monad<M>,
        f: FunctionK<M, N>
    ): Kind2<Kind<ForKleisli, D>, N, A> = Kleisli(AndThen(fix().run).andThen { f(it) })
}

fun <D> Kleisli.Companion.mFunctor(): MFunctor<Kind<ForKleisli, D>> = object : KleisliMFunctor<D> {}

// @extension
interface EitherTMFunctor<L> : MFunctor<Kind<ForEitherT, L>> {
    override fun <M, N, A> Kind2<Kind<ForEitherT, L>, M, A>.hoist(
        MM: Monad<M>,
        f: FunctionK<M, N>
    ): Kind2<Kind<ForEitherT, L>, N, A> = EitherT(f(fix().value()))
}

fun <L> EitherT.Companion.mFunctor(): MFunctor<Kind<ForEitherT, L>> = object : EitherTMFunctor<L> {}

// @extension
interface StateTMFunctor<S> : MFunctor<Kind<ForStateT, S>> {
    override fun <M, N, A> Kind2<Kind<ForStateT, S>, M, A>.hoist(
        MM: Monad<M>,
        f: FunctionK<M, N>
    ): Kind2<Kind<ForStateT, S>, N, A> = StateT(AndThen(fix().runF).andThen { f(it) })
}

fun <S> StateT.Companion.mFunctor(): MFunctor<Kind<ForStateT, S>> = object : StateTMFunctor<S> {}

// @extension
interface WriterTMFunctor<W> : MFunctor<Kind<ForWriterT, W>> {
    override fun <M, N, A> Kind2<Kind<ForWriterT, W>, M, A>.hoist(
        MM: Monad<M>,
        f: FunctionK<M, N>
    ): Kind2<Kind<ForWriterT, W>, N, A> = WriterT(f(fix().value()))
}

fun <W> WriterT.Companion.mFunctor(): MFunctor<Kind<ForWriterT, W>> = object : WriterTMFunctor<W> {}

// @extension
interface RoseMFunctor : MFunctor<ForRose> {
    override fun <M, N, A> Kind2<ForRose, M, A>.hoist(MM: Monad<M>, f: FunctionK<M, N>): Kind2<ForRose, N, A> =
        Rose(MM.run { f.invoke(fix().runRose.map { RoseF(it.res, it.shrunk.map { it.hoist(MM, f).fix() }) }) })
}

fun Rose.Companion.mFunctor(): MFunctor<ForRose> = object : RoseMFunctor {}

// @extension
interface GenTMFunctor : MFunctor<ForGenT> {
    override fun <M, N, A> Kind2<ForGenT, M, A>.hoist(MM: Monad<M>, f: FunctionK<M, N>): Kind2<ForGenT, N, A> =
        GenT(AndThen(fix().runGen).andThen {
            Rose.mFunctor().run {
                it.hoist(OptionT.monad(MM), object : FunctionK<OptionTPartialOf<M>, OptionTPartialOf<N>> {
                    override fun <A> invoke(fa: Kind<OptionTPartialOf<M>, A>): Kind<OptionTPartialOf<N>, A> =
                        OptionT.mFunctor().run { fa.fix().hoist(MM, f) }
                }).fix()
            }
        })
}

fun GenT.Companion.mFunctor(): MFunctor<ForGenT> = object : GenTMFunctor {}

/**
 * Distribute a monad transformer through another monad.
 */
interface MonadTransDistributive<G> {

    /**
     * Distributing an effect through a structure is the dual of traverse. This just lifts that process to distributing
     *  a transformer instead of an effect.
     *
     * If you are trying to understand it I'd recommend looking at an instance and doing every step one by one and inspecting
     *  the type returned (by using something like .let {} to let intellij display it nicely.
     */
    fun <F, M, A> Kind2<G, Kind<F, M>, A>.distributeT(
        MTF: MonadTrans<F>,
        MFunc: MFunctor<F>,
        MM: Monad<M>,
        MT: MonadFromMonadTrans<F>
    ): Kind2<F, Kind<G, M>, A>

    interface MonadFromMonadTrans<F> {
        fun <M> monad(MM: Monad<M>): Monad<Kind<F, M>>
    }
}

// @extension
interface OptionTMonadTransDistributive : MonadTransDistributive<ForOptionT> {
    override fun <F, M, A> Kind2<ForOptionT, Kind<F, M>, A>.distributeT(
        MTF: MonadTrans<F>,
        MFunc: MFunctor<F>,
        MM: Monad<M>,
        MT: MonadTransDistributive.MonadFromMonadTrans<F>
    ): Kind2<F, Kind<ForOptionT, M>, A> =
        MFunc.run {
            fix().value().hoist(MM, object : FunctionK<M, OptionTPartialOf<M>> {
                override fun <A> invoke(fa: Kind<M, A>): Kind<OptionTPartialOf<M>, A> =
                    OptionT.monadTrans().run { fa.liftT(MM) }
            }).let {
                MT.monad(OptionT.monad(MM)).run {
                    it.flatMap { MTF.run { OptionT(MM.just(it)).liftT(OptionT.monad(MM)) } }
                }
            }
        }
}

fun OptionT.Companion.monadTransDistributive(): MonadTransDistributive<ForOptionT> =
    object : OptionTMonadTransDistributive {}

// @extension
interface EitherTMonadTransDistributive<L> : MonadTransDistributive<Kind<ForEitherT, L>> {
    override fun <F, M, A> Kind2<Kind<ForEitherT, L>, Kind<F, M>, A>.distributeT(
        MTF: MonadTrans<F>,
        MFunc: MFunctor<F>,
        MM: Monad<M>,
        MT: MonadTransDistributive.MonadFromMonadTrans<F>
    ): Kind2<F, Kind<Kind<ForEitherT, L>, M>, A> =
        MFunc.run {
            fix().value().hoist(MM, object : FunctionK<M, EitherTPartialOf<L, M>> {
                override fun <A> invoke(fa: Kind<M, A>): Kind<EitherTPartialOf<L, M>, A> =
                    EitherT.monadTrans<L>().run { fa.liftT(MM) }
            }).let {
                MT.monad(EitherT.monad<L, M>(MM)).run {
                    it.flatMap { MTF.run { EitherT(MM.just(it)).liftT(EitherT.monad<L, M>(MM)) } }
                }
            }
        }
}

fun <L> EitherT.Companion.monadTransDistributive(): MonadTransDistributive<Kind<ForEitherT, L>> =
    object : EitherTMonadTransDistributive<L> {}

// @extension
interface WriterTMonadTransDistributive<W> : MonadTransDistributive<Kind<ForWriterT, W>> {
    fun MW(): Monoid<W>

    override fun <F, M, A> Kind2<Kind<ForWriterT, W>, Kind<F, M>, A>.distributeT(
        MTF: MonadTrans<F>,
        MFunc: MFunctor<F>,
        MM: Monad<M>,
        MT: MonadTransDistributive.MonadFromMonadTrans<F>
    ): Kind2<F, Kind<Kind<ForWriterT, W>, M>, A> =
        MFunc.run {
            fix().value().hoist(MM, object : FunctionK<M, WriterTPartialOf<W, M>> {
                override fun <A> invoke(fa: Kind<M, A>): Kind<WriterTPartialOf<W, M>, A> =
                    WriterT.monadTrans(MW()).run { fa.liftT(MM) }
            }).let {
                MT.monad(WriterT.monad(MM, MW())).run {
                    it.flatMap { MTF.run { WriterT(MM.just(it)).liftT(WriterT.monad(MM, MW())) } }
                }
            }
        }
}

fun <W> WriterT.Companion.monadTransDistributive(MW: Monoid<W>): MonadTransDistributive<Kind<ForWriterT, W>> =
    object : WriterTMonadTransDistributive<W> {
        override fun MW(): Monoid<W> = MW
    }

// @extension
interface KleisliMonadTransDistributive<D> : MonadTransDistributive<Kind<ForKleisli, D>> {
    override fun <F, M, A> Kind2<Kind<ForKleisli, D>, Kind<F, M>, A>.distributeT(
        MTF: MonadTrans<F>,
        MFunc: MFunctor<F>,
        MM: Monad<M>,
        MT: MonadTransDistributive.MonadFromMonadTrans<F>
    ): Kind2<F, Kind<Kind<ForKleisli, D>, M>, A> =
        Kleisli(AndThen(fix().run).andThen {
            MFunc.run {
                it.hoist(MM, object : FunctionK<M, KleisliPartialOf<D, M>> {
                    override fun <A> invoke(fa: Kind<M, A>): Kind<KleisliPartialOf<D, M>, A> =
                        Kleisli.monadTrans<D>().run { fa.liftT(MM) }
                }).let { MM.just(it) }
            }
        }).let {
            MT.monad(Kleisli.monad<D, M>(MM)).run {
                MTF.run { it.liftT(Kleisli.monad<D, M>(MM)) }.flatten()
            }
        }
}

fun <D> Kleisli.Companion.monadTransDistributive(): MonadTransDistributive<Kind<ForKleisli, D>> =
    object : KleisliMonadTransDistributive<D> {}

// @extension
interface StateTMonadTransDistributive<S> : MonadTransDistributive<Kind<ForStateT, S>> {
    override fun <F, M, A> Kind2<Kind<ForStateT, S>, Kind<F, M>, A>.distributeT(
        MTF: MonadTrans<F>,
        MFunc: MFunctor<F>,
        MM: Monad<M>,
        MT: MonadTransDistributive.MonadFromMonadTrans<F>
    ): Kind2<F, Kind<Kind<ForStateT, S>, M>, A> = MT.monad(StateT.monad<S, M>(MM)).fx.monad {
        val s = MTF.run { StateT.get<S, M>(MM).liftT(StateT.monad<S, M>(MM)) }.bind()

        val (sNew, a) = MFunc.run { fix().runF(s).hoist(MM, object : FunctionK<M, StateTPartialOf<S, M>> {
            override fun <A> invoke(fa: Kind<M, A>): Kind<StateTPartialOf<S, M>, A> =
                StateT.monadTrans<S>().run { fa.liftT(MM) }
        }) }.bind()

        MTF.run { StateT.set(MM, sNew).liftT(StateT.monad<S, M>(MM)) }.bind()

        a
    }
}

fun <S> StateT.Companion.monadTransDistributive(): MonadTransDistributive<Kind<ForStateT, S>> =
    object : StateTMonadTransDistributive<S> {}

// @extension
interface RoseMonadTransDistributive : MonadTransDistributive<ForRose> {
    override fun <F, M, A> Kind2<ForRose, Kind<F, M>, A>.distributeT(
        MTF: MonadTrans<F>,
        MFunc: MFunctor<F>,
        MM: Monad<M>,
        MT: MonadTransDistributive.MonadFromMonadTrans<F>
    ): Kind2<F, Kind<ForRose, M>, A> = MFunc.run {
        fix().runRose.hoist(MM, object : FunctionK<M, RosePartialOf<M>> {
            override fun <A> invoke(fa: Kind<M, A>): Kind<RosePartialOf<M>, A> = Rose.monadTrans().run { fa.liftT(MM) }
        }).let {
            MT.monad(Rose.monad(MM)).run {
                MTF.run {
                    it.flatMap {
                        Rose(
                            MM.just(
                                RoseF(
                                    MT.monad(Rose.monad(MM)).just(it.res),
                                    it.shrunk.map { Rose.just(MM, it.distributeT(MTF, MFunc, MM, MT)) })
                            )
                        ).liftT(Rose.monad(MM)).flatten()
                    }
                }
            }
        }
    }
}

fun Rose.Companion.monadTransDistributive(): MonadTransDistributive<ForRose> = object : RoseMonadTransDistributive {}

// @extension
interface GenTMonadTransDistributive : MonadTransDistributive<ForGenT> {

    override fun <F, M, A> Kind2<ForGenT, Kind<F, M>, A>.distributeT(
        MTF: MonadTrans<F>,
        MFunc: MFunctor<F>,
        MM: Monad<M>,
        MT: MonadTransDistributive.MonadFromMonadTrans<F>
    ): Kind2<F, Kind<ForGenT, M>, A> = GenT(AndThen(fix().runGen).andThen {
        Rose.mFunctor().run {
            it.hoist(
                OptionT.monad(MT.monad(MM)),
                object : FunctionK<OptionTPartialOf<Kind<F, M>>, Kind<F, OptionTPartialOf<M>>> {
                    override fun <A> invoke(fa: Kind<OptionTPartialOf<Kind<F, M>>, A>): Kind<Kind<F, OptionTPartialOf<M>>, A> =
                        OptionT.monadTransDistributive().run {
                            fa.distributeT(MTF, MFunc, MM, MT)
                        }
                }).let {
                it.fix()
                Rose.monadTransDistributive().run {
                    it.distributeT(
                        MTF,
                        MFunc,
                        OptionT.monad(MM),
                        MT
                    ).let {
                        MFunc.run {
                            it.hoist(
                                Rose.monad(OptionT.monad(MM)),
                                object : FunctionK<RosePartialOf<OptionTPartialOf<M>>, GenTPartialOf<M>> {
                                    override fun <A> invoke(fa: Kind<RosePartialOf<OptionTPartialOf<M>>, A>): Kind<GenTPartialOf<M>, A> =
                                        GenT { _ -> fa.fix() }
                                }).let {
                                Rose.just(OptionT.monad(MM), it)
                            }
                        }
                    }
                }
            }
        }
    }).let {
        MTF.run {
            MT.monad(GenT.monad(MM)).run {
                it.liftT(GenT.monad(MM)).flatten()
            }
        }
    }
}

fun GenT.Companion.monadTransDistributive(): MonadTransDistributive<ForGenT> = object : GenTMonadTransDistributive {}
