---
layout: docs-incubator
title: Why another property testing library?
permalink: /check/why-another-property-testing-library
---

# Why make another property testing library?

> Or better put: What makes *arrow-check* unique/better than other options in the jvm world?

I will be drawing comparisons with *kotest* for most of this document for two reasons:
I am much more familiar with *kotest* than other alternatives and kotest is by far the most significant library offering property based testing for kotlin.
> *kotest* is a great testing framework and while I think their property tests are lacking, their test runner capabilities are great and I very much recommend using it to run *arrow-check*s tests.

Now with that out of the way let us take a look at how the two frameworks differ:

## The size parameter

Generators in *arrow-check* are parameterized by both a random seed and a size.
This has two implications:
- Every test run by *arrow-check* can be deterministically rerun with a given seed and size.
*kotest* has since added a similar parameter, however in *arrow-check* the size and seed affect the entire test, whereas in *kotest* it affects single generators.
Thus it is significantly easier to to rerun *arrow-check* tests.
- The other benefit of a size parameter is that a generator can increase the size of the generated input over time *or* guarantee termination of recursive generators.
Both of these things are simply not possible in *kotest*. 

## Shrinking

*arrow-check* is similar to *kotest* as in it offers automatic shrinking of inputs generated. However the two differ substantially:

In *arrow-check* shrinkers are recursive, whereas *kotest* shrinks only the top level. This is most notable when looking at collections:
```kotlin:ank:silent
import arrow.check.check
check {
  //sampleStart
  val l = forAll { int(0..100).list(0..100) }.bind()
  assert(l.size == 0).bind()
  //sampleEnd
}.attempt().unsafeRunSync()
```
```
🞬 <interactive> failed after 2 tests 12 shrinks.
forAll = [0]
```
Say this generates `[100, 5, 7]` and thus fails. *kotest* will shrink this to a smaller list, likely `[100]`.
*arrow-check* on the other hand will shrink the inner generator as well so we get `[0]`.

With integers this may not seem substantial, but any larger datatype and the benefits of recursive shrinking become much clearer.

Also consider another case:
```kotlin:ank:silent
check {
  //sampleStart
  val l = forAll { int(0..100).list(1..100) }.bind()
  assert(l[0] <= 10).bind()
  //sampleEnd
}.attempt().unsafeRunSync()
```
```
🞬 <interactive> failed after 8 tests 33 shrinks.
forAll = [11]
```
This will fail for any list larger than one where the first item is larger than 10.
A recursive shrinker will shrink a value like `[100, 5, 7]` to `[11]` but less advanced shrinkers will produce `[100]` which makes it harder to parse failure.

> Another fairly subjective point: *arrow-check*s shrinking is much better.
> It can easily compose any number of generators and shrink them towards arbitrary origins.
> On top of that it is extensible, one can easily interleave custom shrinking with existing shrinking of a generator, or even throw away generated shrinking for a better custom method.

## Combinators

Two combinators that work quite different in *arrow-check* compared to *kotest* are `flatMap` and `filter`:

### filter

Filter in *arrow-check* will recursively try to produce a value for which a predicate holds until a specific depth:
This means filtering an infinite sequence will eventually produce either a result or fail.
This is not the same in *kotest* where an overly restrictive filter can block a test for a long time.

### flatMap

The `flatMap` function in *kotest* can hardly be called flatMap at all. It has two major flaws:
- It does not accept a `Gen<A>` as the result. This means we cannot ever chain generators.
This is the main use case behind `flatMap` and not doing exactly that will be misleading.
- It throws away all shrinking. Yes literally. When you `flatMap` the new values will not shrink.

In contrast *arrow-check* `flatMap` looks like this: `fun <A, B> Gen<A>.flatMap(f: (A) -> Gen<B>): Gen<B>`.
This will recursively and lazily apply the flatMap function to all values including shrunk values.
The resulting generator retains all shrinking and interleaves values correctly.

### Others

*arrow-check*s `Gen` is an instance of many type classes from arrow (and a "mostly" lawful one at that).
This provides access to a lot of combinators from those classes.
It would be possible to do the same for *kotest* however the inherent design makes lawful implementations harder.

## Coverage

Testing the coverage of a generator is incredibly useful.
It allows verifying that a test ran with enough meaningful input to consider it successful.

*arrow-check* provides first class support for many types of coverage checks, but what should be especially noted is *arrow-checks* ability to run a test until it either passes coverage conditions or it it considers it impossible up to some degree.

*kotest* does provide some basic coverage checking, but not to the extend *arrow-check* offers.

## Output

There is no competition here. Again this is subjective, but the console output produced by *arrow-check* is on another level.

This is achieved by a few things:
- *arrow-check* uses a prettyprinter to do all of its layout management.
This means any output is aligned automatically and without manual effort.
- Every value *arrow-check* uses `toString` on is parsed and prettified.
This is used to produce both readable output and to calculate git-like diffs of output.

These two combined make the console output of *arrow-check* very easy to parse and allows finding failure quickly.

## Function generation

*arrow-check* offers a feature to generate arbitrary deterministic and non-constant functions.
```kotlin:ank
import arrow.check.gen.*
//sampleStart
check {
  val f: (Int) -> String = forAllFn(
    Gen.monadGen {
      ascii().string(0..100)
        .toFunction(Int.func(), Int.coarbitrary())
    }
  ).bind()
  val result = f(1)
}
//sampleEnd
  .attempt().unsafeRunSync()
```

This is **not** the same as `ascii().string(0..100).map { str -> { _ -> str } }`.
This is a constant function and thus not very useful at all.

In contrast *arrow-check*s function returns a different, yet deterministic, output for different inputs:
If `f(1)` returns `"abc"` then `f(2)` will likely return something different. But it is deterministic so `f(1) => "abc"` is guaranteed.

And the best thing? These functions can shrink on failure and can be shown in text form to understand failure.

## Extensibility

This one is crucial. In *arrow-check* both generators `GenT<M, A>` and property tests `PropertyT<M, A>` can be extended with arbitrary types for `M`.
The default value is `IO`. It allows running `suspend` functions freely inside both generators and properties.

But this is not the limit: We can add different effects on top to change the behaviour of both types.

-- TODO example for extending both PropertyT and GenT
-- PropertyT with a simple state that counts number of tests
-- 

<!--- 
Add later on when that feature is complete.
## State machine testing
-->
