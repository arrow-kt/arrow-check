package arrow.check.gen

import arrow.core.Tuple2
import arrow.core.toT
import kotlinx.coroutines.flow.*

typealias Shrinks<A> = Flow<Rose<A>?>

fun <A, B, C> Shrinks<A>.zipWith(other: Shrinks<B>, f: (A, B) -> C): Shrinks<C> = zip(other) { l, r ->
    if (l === null || r === null) null
    else l.zip(r, f)
}
fun <A, B> Shrinks<A>.mapRose(f: suspend (Rose<A>?) -> Rose<B>?): Shrinks<B> =
    map { it?.let { f(it) } }

data class Rose<A>(val res: A, val shrinks: Shrinks<A> = emptyFlow()) {
    fun <B> map(f: (A) -> B): Rose<B> = Rose(f(res), shrinks.map { it?.map(f) })

    fun <B, C> zip(other: Rose<B>, f: (A, B) -> C): Rose<C> =
        Rose(f(res, other.res), shrinks.zipWith(other.shrinks) { a, b -> f(a, b) })

    suspend fun <B> flatMap(f: suspend (A) -> Rose<B>?): Rose<B>? = f(res)?.let { roseB ->
        Rose(roseB.res, shrinks.mapRose { it?.flatMap(f) }.onCompletion { emitAll(roseB.shrinks) })
    }

    fun expand(f: (A) -> Sequence<A>): Rose<A> =
        Rose(res, shrinks.onCompletion { emitAll(f(res).asFlow().map { Rose(it).expand(f) } as Shrinks<A>) })

    fun prune(n: Int): Rose<A> =
        if (n <= 0) Rose(res)
        else Rose(res, shrinks.mapRose { it?.prune(n - 1) })

    fun <B> filterMap(f: (A) -> B?): Rose<B>? =
        f(res)?.let { Rose(it, shrinks.mapRose { it?.filterMap(f) }) }
}

internal fun <A> List<A>.uncons(): Tuple2<A, List<A>> = this[0] toT drop(1)
