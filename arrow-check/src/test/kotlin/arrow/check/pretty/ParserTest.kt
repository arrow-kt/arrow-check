package arrow.check.pretty

import arrow.check.PropertySpec
import arrow.check.gen.GenT
import arrow.check.gen.GenTOf
import arrow.check.gen.Range
import arrow.check.gen.monadGen
import arrow.typeclasses.Monad
import kparsec.renderPretty
import kparsec.runParser
import kparsec.stream
import pretty.doc

fun <M> genAnyPrims(MM: Monad<M>): List<GenTOf<M, Any>> = GenT.monadGen(MM).run {
    listOf(
        byte(Range.constant(0, Byte.MIN_VALUE, Byte.MAX_VALUE)),
        short(Range.constant(0, Short.MIN_VALUE, Short.MAX_VALUE)),
        int(Range.constant(0, Int.MIN_VALUE, Int.MAX_VALUE)),
        long(Range.constant(0, Long.MIN_VALUE, Long.MAX_VALUE)),
        float(Range.constant(0f, Float.MIN_VALUE, Float.MAX_VALUE)),
        double(Range.constant(0.0, Double.MIN_VALUE, Double.MAX_VALUE)),
        unicodeAll(), unicodeAll().string(0..10)
    )
}

fun <M> genAny(MM: Monad<M>): GenT<M, Any> = GenT.monadGen(MM) {
    recursive({ choice(*it.toTypedArray()) }, genAnyPrims(MM)) {
        listOf(
            genAny(MM).option(),
            either(genAny(MM), genAny(MM)),
            validated(genAny(MM), genAny(MM)),
            ior(genAny(MM), genAny(MM)),
            genAny(MM).list(0..10),
            genAny(MM).set(0..10),
            tupledN(genAny(MM), genAny(MM)).map(0..10),
            genAny(MM).nonEmptyList(1..10),
            genAny(MM).id(),
            genAny(MM).const<Any, Any>(),
            tupledN(genAny(MM), genAny(MM)),
            tupledN(genAny(MM), genAny(MM), genAny(MM)),
            tupledN(genAny(MM), genAny(MM), genAny(MM), genAny(MM)),
            tupledN(genAny(MM), genAny(MM), genAny(MM), genAny(MM), genAny(MM))
        )
    }
}

class ParserPropertyTests : PropertySpec({
    "!DiffingEqualValues" {
        val v = forAllT(genAny(MM())).bind()

        classify("Is string-like", v is String || v is Char).bind()

        outputParser().runParser("", v.toString()).fold({
            annotate { it.renderPretty(String.stream()).doc() }.bind()

            if ((v is String && v is Char).not()) failWith("Parser failed to parse value").bind()
        }, {
            if (it is KValue.RawString) failWith("Parser fell back to string").bind()
        })
    }
})