package arrow.check.gen

import arrow.Kind
import arrow.check.gen.instances.rose.birecursive.birecursive
import arrow.core.*
import arrow.core.extensions.fx
import arrow.mtl.typeclasses.nest
import arrow.typeclasses.Applicative
import arrow.typeclasses.Functor
import arrow.typeclasses.Monad

// @higherkind boilerplate
class ForRose private constructor() {
    companion object
}
typealias RoseOf<M, A> = arrow.Kind<RosePartialOf<M>, A>
typealias RosePartialOf<M> = arrow.Kind<ForRose, M>

@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
inline fun <M, A> RoseOf<M, A>.fix(): Rose<M, A> =
    this as Rose<M, A>

data class Rose<M, A>(val runRose: Kind<M, RoseF<A, Rose<M, A>>>) :
    RoseOf<M, A> {

    fun <B> map(MF: Functor<M>, f: (A) -> B): Rose<M, B> = MF.run {
        Rose(runRose.map {
            RoseF(
                f(it.res),
                it.shrunk.map { it.map(MF, f) })
        })
    }

    fun <B> ap(MA: Applicative<M>, ff: Rose<M, (A) -> B>): Rose<M, B> = MA.run {
        Rose(
            MA.mapN(runRose, ff.runRose) { (a, f) ->
                RoseF(
                    f.res(a.res),
                    f.shrunk.map { it.map(MA) { it(a.res) } } +
                            a.shrunk.map { it.ap(MA, ff) }

                )
            }
        )
    }

    /**
     * Parallel shrinking. This breaks monad-applicative consistency laws in GenT because it's used in place of ap there
     */
    fun <B> zipTree(MA: Applicative<M>, ff: () -> Rose<M, B>): Rose<M, Tuple2<A, B>> = MA.run {
        Rose(
            this@Rose.runRose.lazyAp {
                ff().let { ff ->
                    ff.runRose.map { r ->
                        { l: RoseF<A, Rose<M, A>> ->
                            RoseF(
                                l.res toT r.res,
                                l.shrunk.k().map { it.zipTree(MA) { ff } } + r.shrunk.map { this@Rose.zipTree(MA) { it } })
                        }
                    }
                }
            }
        )
    }

    fun <B> flatMap(MM: Monad<M>, f: (A) -> Rose<M, B>): Rose<M, B> =
        Rose(
            MM.fx.monad {
                val rose1 = !runRose
                val rose2 = !f(rose1.res).runRose
                RoseF(
                    rose2.res,
                    rose1.shrunk.map { it.flatMap(MM, f) } + rose2.shrunk
                )
            }
        )

    fun expand(MM: Monad<M>, f: (A) -> Sequence<A>): Rose<M, A> = MM.run {
        Rose(
            runRose.flatMap { r ->
                just(
                    RoseF(
                        r.res,
                        r.shrunk.map { it.expand(MM, f) } +
                                unfoldForest(MM, r.res, f)
                    )
                )
            }
        )
    }

    fun prune(MM: Monad<M>, n: Int): Rose<M, A> =
        if (n <= 0) Rose(
            MM.run {
                runRose.map {
                    RoseF(
                        it.res,
                        emptySequence<Rose<M, A>>()
                    )
                }
            }
        )
        else Rose(
            MM.run {
                runRose.map {
                    RoseF(
                        it.res,
                        it.shrunk.map { it.prune(MM, n - 1) })
                }
            }
        )

    companion object {
        fun <M, A> just(AM: Applicative<M>, a: A): Rose<M, A> =
            Rose(
                AM.just(
                    RoseF(
                        a,
                        emptySequence()
                    )
                )
            )

        fun <M, A> unfold(MM: Monad<M>, a: A, f: (A) -> Sequence<A>): Rose<M, A> =
            Rose.birecursive<M, A>(MM).run {
                a.ana {
                    MM.just(RoseF(it, f(it))).nest()
                }
            }

        fun <M, A> unfoldForest(MM: Monad<M>, a: A, f: (A) -> Sequence<A>): Sequence<Rose<M, A>> =
            f(a).map { unfold(MM, it, f) }

        fun <M, A> liftF(FF: Functor<M>, fa: Kind<M, A>): Rose<M, A> = FF.run {
            Rose(fa.map { RoseF(it, emptySequence<Rose<M, A>>()) })
        }
    }
}

fun <A> Sequence<A>.splits(): Sequence<Tuple3<Sequence<A>, A, Sequence<A>>> =
    firstOrNull().toOption().fold({
        emptySequence()
    }, { x ->
        sequenceOf(Tuple3(emptySequence<A>(), x, drop(1)))
            // flatMap for added laziness
            .flatMap {
                sequenceOf(it) + drop(1).splits().map { (a, b, c) ->
                    Tuple3(sequenceOf(x) + a, b, c)
                }
            }
    })

fun <M, A> Sequence<RoseF<A, Rose<M, A>>>.dropOne(MM: Monad<M>): Sequence<Rose<M, Sequence<A>>> =
    SequenceK.fx {
        val (xs, _, zs) = !splits().k()
        Rose(MM.just((xs + zs).interleave(MM)))
    }

fun <M, A> Sequence<RoseF<A, Rose<M, A>>>.shrinkOne(MM: Monad<M>): Sequence<Rose<M, Sequence<A>>> =
    SequenceK.fx {
        val (xs, y, zs) = !splits().k()
        val y1 = !y.shrunk.k()
        Rose(
            MM.run {
                y1.runRose.map { (xs + sequenceOf(it) + zs).interleave(MM) }
            }
        )
    }

fun <M, A> Sequence<RoseF<A, Rose<M, A>>>.interleave(MM: Monad<M>): RoseF<Sequence<A>, Rose<M, Sequence<A>>> =
    RoseF(
        this.map { it.res },
        dropOne(MM) + shrinkOne(MM)
    )

// -------- RoseF

class ForRoseF private constructor()
typealias RoseFOf<A, F> = arrow.Kind<RoseFPartialOf<A>, F>
typealias RoseFPartialOf<A> = arrow.Kind<ForRoseF, A>

@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
inline fun <A, F> RoseFOf<A, F>.fix(): RoseF<A, F> =
    this as RoseF<A, F>

/**
 * Recursive data structure.
 * At every level keeps both the current tested value and (lazily) the shrunk values
 */
data class RoseF<A, F>(val res: A, val shrunk: Sequence<F>) : RoseFOf<A, F> {
    fun <B> map(f: (F) -> B): RoseF<A, B> =
        RoseF(res, shrunk.map(f))

    companion object
}
