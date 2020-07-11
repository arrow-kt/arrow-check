---
layout: docs-incubator
title: Writing good generators
permalink: /check/writing-good-generators
---

# Writing good generators

This guide aims to expose some common gotchas while writing generators and how to avoid them.

Table of contents:
- Avoiding monadic composition
- Avoid manually discarding values when filtering
- Use `forAllT` when dealing with a large recursive generator
- Use the inbuilt recursive function for recursive generators

## Avoiding monadic composition

Suppose we want to generate two **independent** values, like two numbers:
```kotlin:ank
//sampleStart
val gen = Gen.monadGen {
  fx {
    val a = int(0..10).bind()
    val b = int(0..10).bind()
    Pair(a, b)
  }
}
//sampleEnd
gen.print()
```
This will indeed work perfectly fine. However as you can see in the output the shrunk values are not ideal.
This has a very specific reason: The code above is equal to `int(range).flatMap { x -> int(range).map { y -> Pair(x, y) } }`.
The use of `flatMap` implies that the second generator **depends** on the result of the first.
This means a shrinker cannot go back to first value after it is done shrinking it.

However this is only a problem if the two values actually **depend** on each other (which can also be worked around).
But this is not the case with the above, and not with most generators. If we instead use different combinators to compose them we get good results:
```kotlin:ank
//sampleStart
val gen = Gen.monadGen {
  tupled(int(0..10), int(0..10))
}
val gen2 = Gen.monadGen {
  mapN(int(0..10), int(0..10)) { (a, b) -> Pair(a, b) }
}
//sampleEnd
gen.print()
```
By using `mapN` to combine the generators we can combine **independent** generators, this use case is sufficient for most if not all types of data classes.
> If you are familiar with arrow you may see that the difference between `flatMap` and `mapN` is the difference between `Monad` and `Applicative`.
> In fact all `Applicative` methods offer this parallel shrinking, where as `Monad` only offer sequential/suboptimal shrinking.

For the most part the bad shrinking produced by `flatMap`/`fx` is not a huge problem, so do not worry all that much about this.
But when you start seeing bad shrinking results this is something to consider.

## Avoid manually discarding values when filtering

There are currently two options when it comes to filtering a generator.
These two options produce similar, if not equal results:
```kotlin:ank
//sampleStart
val gen = Gen.monadGen {
  int(0..100).flatMap { a -> if (a > 10) pure(a) else discard() }
}
val gen1 = Gen.monadGen {
  int(0..100).filter { a -> a > 10 }
}
//sampleEnd
gen.print()
gen1.print()
```
Both of these generators do roughly the same. However the first one will have a very high discard ratio.
The filter method on the other hand recursively expands the search size of a generator and tries the value against the filter before discarding it.

So in short: Always prefer filter, but also try to avoid it if you can.

## Use `forAllT` when dealing with a large recursive generator

When writing a recursive generator for collections or recursive types a generator can quickly lead to stackoverflows.

But feat not, there is an easy solution: For the most part generators are defined with the typealias `Gen<A> = GenT<ForId, A>`.
This basically states that the generator will not execute any additional effects when generating `A`.

If we instead use a different effect such as `ForIO` (which will be used at the top level anyway, so there is no cost) we can take care of arrow's stacksafety guarantees when using `IO`.

In general you need few changes:
- `Gen<A> -> GenT<ForIO, A>`
- `forAll(gen) -> forAllT(gen)`
- `monadGen() -> monadGen(IO.monad())`

This should be all that is necessary to change a `Gen<A>` to `GenT<ForIO, A>`.

Lastly if you want to compose any `GenT<M, A>` with other `Gen<A>`'s you can use `generalize(M.monad())` to turn a `Gen<A>` into `GenT<M, A>`.
But this will not add stacksafety, so you do actually need the method described above.

## Use the inbuilt recursive function for recursive generators

When defining a recursive generator it might be tempting to write:
```kotlin:ank
data class IntTree(val v: Int, val nodes: List<IntTree>)
//sampleStart
fun gen(): GenT<ForIO, IntTree> = GenT.monadGen(IO.monad()) {
  fx {
    val a = int(0..100).bind()
    // recursively generate branches
    val branches = gen().list(0..100).bind()
    IntTree(a, branches)
  }
}
//sampleEnd
gen.print()
```
This generator is perfectly fine, however this may not actually terminate. A solution to this is tweaking the size of the recursive generator invocation, so that trees become smaller the deeper they are.

```kotlin:ank
data class IntTree(val v: Int, val nodes: List<IntTree>)
//sampleStart
fun gen(): GenT<ForIO, IntTree> = GenT.monadGen(IO.monad()) {
  sized { s ->
    fx {
      val a = int(0..100).bind()
      // recursively generate branches
      val branches = gen().list(0..100)
        .resize(Size(s.unSize - 1)).bind()
      IntTree(a, branches)
    }
  }
}
//sampleEnd
gen.print()
```

This will now generate trees that becomes smaller and smaller the deeper it becomes, so eventually it will be run with a size of 0 which forces an empty list and thus terminates.

 However this is very annoying to do manually, so there exists a better way:
 ```kotlin:ank
data class IntTree(val v: Int, val nodes: List<IntTree>)
//sampleStart
fun gen(): GenT<ForIO, IntTree> = GenT.monadGen(IO.monad()) {
  recursive(
    { opts -> choice(*opts.toTypedArray()) },
    listOf(int(0..100).map { IntTree(it, emptyList()) }),
    { mapN(int(0..100), gen()) { (a, n) -> IntTree(a, n) } }
  )
}
//sampleEnd
gen.print()
```
While on the surface this looks more complex than the previous option this does solve a few common problems:
- The first argument is a choice function. Given a list of generators you can freely choose between them. Here we use choice, which models unweighted choice between all entries.
- The second argument is a list of terminal generators that do not recur. Here we only have a tree with no branches, but this can be extended arbitrarily.
- The third and final argument is a function to a list of recursive generators. The function is used to defer creating the generators to avoid running into stackoverflows.

When given a size larger than 0 `recursive` will pass both the recursive and terminal generators to the choice function, allowing recursion.
It also decrements the size by multiplying it with the *golden-ratio*. This offers good behavior in most cases.

When the size approaches 0 the generator will terminate by only passing the terminal generators to the choice function.

For small recursive datatypes the difference may not seem large, but think of the same example with a large datatype representing something like expressions.
In those cases this combinator will improve readability. Also by avoiding the manual size parameter manipulation the generator becomes less error-prone.
