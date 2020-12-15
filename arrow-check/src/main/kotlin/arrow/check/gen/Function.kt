package arrow.check.gen

import arrow.core.*
import arrow.core.extensions.eval.monad.flatten
import arrow.recursion.pattern.ListF
import arrow.syntax.collections.tail
import arrow.typeclasses.Show
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlin.math.pow

fun <R, A, B> Gen<R, B>.toFunction(tf: ToFunction<A>): Gen<R, Fun<A, B>> =
    map2(
        Gen { inp ->
            Rose(
                tf.run {
                    toFunction { a ->
                        // Why? TODO Explain. This, to my knowledge, is not avoidable without significant changes
                        runBlocking { this@toFunction.vary(a).runGen(inp) }
                    }
                }
            ).expand { fn -> fn.shrink { it?.shrinks ?: emptyFlow() } }
        }
    ) { def, fn: Fn<A, Rose<B>?> -> Fun(def, fn) }

class Fun<in A, out B>(val default: B, val fn: Fn<A, Rose<B>?>) {
    operator fun component1(): (A) -> B =
        fn.abstract(Rose(default))
            .andThen { it.value()?.res ?: throw IllegalStateException("Fun.invoke did not generate a value!") }

    override fun toString(): String = show(Show.any(), Show.any())
}

fun <A, B> Fun<A, B>.show(SA: Show<A>, SB: Show<B>): String =
    fn.table().let { ls ->
        ls.toList()
            .mapNotNull { (k, v) -> v?.let { k to v.res } }
            .map { (k, v) ->
                SA.run { k.show() } + " -> " + SB.run { v.show() }
            } + listOf("_ -> " + SB.run { default.show() })
    }.toString()

sealed class Fn<in A, out B> {
    object NilFn : Fn<Any?, Nothing>()
    class UnitFn<out B>(val b: Eval<B>) : Fn<Unit, B>()
    class EitherFn<in A, in B, out C>(
        val lFn: Fn<A, C>,
        val rFn: Fn<B, C>
    ) : Fn<Either<A, B>, C>()

    class PairFn<in A, in B, out C>(
        val pairFn: Fn<A, Fn<B, C>>
    ) : Fn<Pair<A, B>, C>()

    class MapFn<A, B, out C>(
        val f: (A) -> Eval<B>,
        val cf: (B) -> A,
        val fn: Fn<B, C>
    ) : Fn<A, C>()

    fun <C> map(f: (B) -> C): Fn<A, C> = when (this) {
        NilFn -> NilFn
        is UnitFn -> UnitFn(b.map(f)) as Fn<A, C>
        is EitherFn<*, *, B> -> EitherFn(
            (lFn as Fn<A, B>).map(f),
            (rFn as Fn<A, B>).map(f)
        ) as Fn<A, C>
        is PairFn<*, *, B> ->
            PairFn(pairFn.map { (it as Fn<A, B>).map(f) }) as Fn<A, C>
        is MapFn<A, *, B> ->
            MapFn(
                f as (A) -> Eval<Any?>, cf as (Any?) -> A, fn.map(f) as Fn<Any?, C>
            )
    }
}

fun <A, B> Fn<A, B>.abstract(def: B): (A) -> Eval<B> = when (this) {
    Fn.NilFn -> { _: A -> Eval.now(def) }
    is Fn.UnitFn -> { _: A -> b }
    is Fn.EitherFn<*, *, B> -> { a: A ->
        (a as Either<Any?, Any?>).fold({
            (lFn as Fn<Any?, B>).abstract(def)(it)
        }, {
            (rFn as Fn<Any?, B>).abstract(def)(it)
        })
    }
    is Fn.PairFn<*, *, B> -> { a: A ->
        (a as Pair<Any?, Any?>).let { (a, b) ->
            (pairFn as Fn<Any?, Fn<Any?, B>>)
                .map { it.abstract(def)(b) }
                .abstract(Eval.now(def))(a)
                .flatten()
        }
    }
    is Fn.MapFn<A, *, B> -> { a: A ->
        f(a).flatMap { i ->
            (fn as Fn<Any?, B>).abstract(def)(i)
        }
    }
}

fun <A, B> Fn<A, B>.shrink(shrinkB: (B) -> Flow<B>): Flow<Fn<A, B>> = when (this) {
    Fn.NilFn -> emptyFlow()
    is Fn.UnitFn -> flowOf(Fn.NilFn as Fn<A, B>).onCompletion { emitAll(shrinkB(b.value()).map { Fn.UnitFn(Eval.now(it)) as Fn<A, B> }) }
    is Fn.EitherFn<*, *, B> ->
        flowOf(combineFn(Fn.NilFn, rFn) as Fn<A, B>, combineFn(lFn, Fn.NilFn) as Fn<A, B>)
            .onCompletion {
                emitAll(lFn.shrink(shrinkB).map { combineFn(it, rFn) as Fn<A, B> })
                emitAll(rFn.shrink(shrinkB).map { combineFn(lFn, it) as Fn<A, B> })
            }
    is Fn.PairFn<*, *, B> -> (pairFn as Fn<Any?, Fn<Any?, B>>).shrink { fn ->
        fn.shrink(shrinkB)
    }.map {
        when (it) {
            Fn.NilFn -> Fn.NilFn
            else -> Fn.PairFn(it) as Fn<A, B>
        }
    }
    is Fn.MapFn<A, *, B> -> (fn as Fn<Any?, B>).shrink(shrinkB).map {
        when (it) {
            Fn.NilFn -> Fn.NilFn
            else -> Fn.MapFn(f, cf as (Any?) -> A, it)
        }
    }
}

private fun <A, B, C> combineFn(l: Fn<A, C>, r: Fn<B, C>): Fn<Either<A, B>, C> =
    if (l is Fn.NilFn && r is Fn.NilFn) Fn.NilFn
    else Fn.EitherFn(l, r)

fun <A, B> Fn<A, B>.table(): Map<A, B> = when (this) {
    Fn.NilFn -> emptyMap()
    is Fn.UnitFn -> mapOf(Unit to b.value()) as Map<A, B>
    is Fn.EitherFn<*, *, B> ->
        ((lFn as Fn<Any, B>).table().mapKeys { it.key.left() } +
                (rFn as Fn<Any?, B>).table().mapKeys { it.key.right() }) as Map<A, B>
    is Fn.PairFn<*, *, B> ->
        (pairFn as Fn<Any?, Fn<Any?, B>>).table().toList().flatMap { (k1, fn) ->
            fn.table().toList().map { (k2, v) -> (k1 to k2) to v }
        }.toMap() as Map<A, B>
    is Fn.MapFn<A, *, B> -> (fn as Fn<Any?, B>).table().mapKeys { (cf as (Any?) -> A)(it.key) }
}

interface ToFunction<A> {
    fun <R, B> Gen<R, B>.vary(a: A): Gen<R, B>

    fun <B> toFunction(f: (A) -> B): Fn<A, B>

    fun <C> map(f: (A) -> C, cf: (C) -> A): ToFunction<C> = object : ToFunction<C> {
        override fun <B> toFunction(ff: (C) -> B): Fn<C, B> = funMap(this@ToFunction, cf, f, ff)

        override fun <R, B> Gen<R, B>.vary(a: C): Gen<R, B> = this@ToFunction.run { vary(cf(a)) }
    }
}

private fun <A, B, C> funMap(fb: ToFunction<B>, f: (A) -> B, cF: (B) -> A, g: (A) -> C): Fn<A, C> =
    funMapRec(fb, f.andThen { Eval.now(it) }, cF, g)

private fun <A, B, C> funMapRec(fb: ToFunction<B>, f: (A) -> Eval<B>, cF: (B) -> A, g: (A) -> C): Fn<A, C> = fb.run {
    Fn.MapFn(f, cF, toFunction(AndThen(cF).andThen(g)))
}

private fun <A, B, C> ((Pair<A, B>) -> C).curry(): ((A) -> ((B) -> C)) =
    AndThen { a -> AndThen { b: B -> a to b }.andThenF(AndThen(this)) }

private fun <A, B, C> funPair(fA: ToFunction<A>, fB: ToFunction<B>, f: (Pair<A, B>) -> C): Fn<Pair<A, B>, C> = fA.run {
    fB.run {
        Fn.PairFn(
            toFunction(f.curry()).map { toFunction(it) }
        )
    }
}

private fun <A, B, C> funEither(fA: ToFunction<A>, fB: ToFunction<B>, f: (Either<A, B>) -> C): Fn<Either<A, B>, C> =
    Fn.EitherFn(
        fA.run { toFunction(AndThen(f).compose { Either.Left(it) }) },
        fB.run { toFunction(AndThen(f).compose { Either.Right(it) }) }
    )

private fun <R, A> Gen<R, A>.variant(l: Long): Gen<R, A> =
    Gen(runGen.compose { (seed, size, env) -> Tuple3(seed.variant(l), size, env) })

fun Unit.toFunction(): ToFunction<Unit> = object : ToFunction<Unit> {
    override fun <B> toFunction(f: (Unit) -> B): Fn<Unit, B> = Fn.UnitFn(Eval.later { f(Unit) })
    override fun <R, B> Gen<R, B>.vary(a: Unit): Gen<R, B> = this
}

fun <L, R> Either.Companion.toFunction(LF: ToFunction<L>, RF: ToFunction<R>): ToFunction<Either<L, R>> =
    object : ToFunction<Either<L, R>> {
        override fun <B> toFunction(f: (Either<L, R>) -> B): Fn<Either<L, R>, B> = funEither(LF, RF, f)
        override fun <R2, B> Gen<R2, B>.vary(a: Either<L, R>): Gen<R2, B> =
            a.fold({ l ->
                LF.run { variant(1).vary(l) }
            }, { r ->
                RF.run { variant(2).vary(r) }
            })
    }

fun <A> NullableToFunction(AF: ToFunction<A>): ToFunction<A?> = Either.toFunction(Unit.toFunction(), AF)
    .map({ it.orNull() }, { it?.let { Either.right(it) } ?: Either.left(Unit) })

fun <A, B> PairToFunction(AF: ToFunction<A>, BF: ToFunction<B>): ToFunction<Pair<A, B>> =
    object : ToFunction<Pair<A, B>> {
        override fun <B1> toFunction(f: (Pair<A, B>) -> B1): Fn<Pair<A, B>, B1> = funPair(AF, BF, f)
        override fun <R, B1> Gen<R, B1>.vary(a: Pair<A, B>): Gen<R, B1> =
            AF.run { BF.run { vary(a.first).vary(a.second) } }
    }

fun Boolean.Companion.toFunction(): ToFunction<Boolean> = Either.toFunction(Unit.toFunction(), Unit.toFunction())
    .map({ it.fold({ false }, { true }) }, { if (it) Either.right(Unit) else Either.left(Unit) })

// Model haskell lists to prevent overflows with strict lists
// This is a bit wasteful, maybe there is a better solution?
// TODO test if this is still needed. At least it works for now
internal data class Stream<A>(val unStream: ListF<A, Eval<Stream<A>>>) {
    fun <B> foldRight(lb: Eval<B>, f: (A, Eval<B>) -> Eval<B>): Eval<B> = when (unStream) {
        is ListF.NilF -> lb
        is ListF.ConsF -> f(unStream.a, unStream.tail.flatMap { it.foldRight(lb, f) })
    }

    companion object {
        fun <A> func(AF: ToFunction<A>): ToFunction<Stream<A>> = object : StreamFunc<A> {
            override fun AF(): ToFunction<A> = AF
        }

        fun <A> fromList(ls: List<A>): Stream<A> =
            if (ls.isEmpty()) Stream(ListF.NilF())
            else Stream<A>(ListF.ConsF(ls.first(), Eval.later { fromList(ls.tail()) }))
    }
}

internal interface StreamFunc<A> : ToFunction<Stream<A>> {
    fun AF(): ToFunction<A>
    override fun <B> toFunction(f: (Stream<A>) -> B): Fn<Stream<A>, B> =
        funMapRec(NullableToFunction(PairToFunction(AF(), this)), {
            when (val l = it.unStream) {
                is ListF.NilF -> Eval.now(null)
                is ListF.ConsF -> l.tail.map { l.a to it }
            }
        }, {
            it?.let { (head, tail) -> Stream(ListF.ConsF(head, Eval.later { tail })) } ?: Stream(ListF.NilF())
        }, f)

    override fun <R, B> Gen<R, B>.vary(a: Stream<A>): Gen<R, B> {
        fun Gen<R, B>.go(a: Stream<A>): Eval<Gen<R, B>> = when (val c = a.unStream) {
            is ListF.NilF -> Eval.now(variant(1))
            is ListF.ConsF -> c.tail.flatMap { tail -> AF().run { variant(2).vary(c.a).go(tail) } }
        }
        return go(a).value()
    }
}

fun <A> ListToFunction(AF: ToFunction<A>): ToFunction<List<A>> = Stream.func(AF)
    .map({
        it.foldRight<List<A>>(Eval.now(emptyList())) { v, acc ->
            acc.map { listOf(v) + it }
        }.value()
    }, { Stream.fromList(it) })

fun Long.Companion.toFunction(): ToFunction<Long> = object : ToFunction<Long> {
    override fun <B> toFunction(f: (Long) -> B): Fn<Long, B> =
        funMap(
            ListToFunction(Boolean.toFunction()),
            { l ->
               l.toULong().toString(2).map { it == '1' }
            }, { bits ->
                bits.fold(0UL to 0) { (acc, nr), v ->
                    if (v) (acc + 2.0.pow(nr).toUInt()) to (nr + 1)
                    else acc to (nr + 1)
                }.first.toLong()
            }, f
        )
    override fun <R, B> Gen<R, B>.vary(a: Long): Gen<R, B> = variant(a)
}

fun Int.Companion.toFunction(): ToFunction<Int> = Long.toFunction().map({ it.toInt() }, { it.toLong() })
