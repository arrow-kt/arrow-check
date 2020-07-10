---
layout: docs-incubator
title: Why property based testing?
permalink: /check/why-property-based-testing
---

```kotlin:ank:silent
import arrow.check.check
```

# Why property-based-testing?

To understand this question lets look at a different model that is widely used: Example based testing.

An example based test is roughly structured like this:

`val example = ...; val result = toTest(example); result == expected`

Most unit tests usually run an example based test for the success path of the function, one or more for the failure path and (if obvious) some for edge cases.

Note the "if obvious", edge cases usually are not. Sure we can all see where `x / y` can go wrong, but this behind several layers of function calls and other indirections will become quite the challenge.

The problem here is that we, the programmer, have to think of all cases manually and have to decide what to test and how.

Another problem with this approach to testing is that defining such test data is trivial with small types like numbers/strings. But easily grows out of hand on larger data classes.
Writing test data by hand is a manual task that might as well be called boilerplate for tests. It is error prone and tedious.

## Properties and generators to the rescue:

The structure of a property test is quite similar to that of an example based test:

`val example = generateExample(); val result = toTest(example); verify(result)`

Except that it is not obvious how those examples are generated! Lets have a look at how this is handled in arrow-check:
```kotlin:ank
//sampleStart
check {
    val example = forAllT { int(-100..100).list(0..100) }.bind() // 1
    // no property based testing intro is complete without a reverse property
    val result = example.reversed() // 2
    // check if reversing again produces the same result
    // This is naive, but this is only for an example anyway.
    example.eqv(result.reversed()).bind() // 3
}
//sampleEnd
    .unsafeRunSync()
```

This will now go and produce a number of lists as inputs and verify that reversing the list twice will yield the original list back.
This is more commonly known as a roundtrip property. (For which there exists [special] support for better errors).

Let's go through it step by step:

- **1:** `forAllT` is one of the main entry points into generating data, it takes a function that has which allows building the generator inline. There are overloads that can take predefined generators as well. You can read more about generators [here].
- **2:** Execute the function that we want to test.
- **3:** Here we use `eqv` to test if the result is equal to the expected output. `eqv` is used instead of `==` because it will print out a git-like diff of the two values when the property fails. You can see this in action [here].

All in all the structure of these tests are very similar to example based tests, except that we define what data to generate instead of defining the data directly.
Also we have to derive correctness of our tested functions as we do not know the exact output and input.

The fact that a reversed list should always be the same size as the input and that the operation should be invertible are both properties of the `reversed` functions which we are testing.

## Defining properties

This one is by far the most confusing part in property based testing.
Instead of providing the test with an expected result we have to somehow assert it from random inputs.

One good way of understanding properties is as requirements:
- A reversed function should always have the same elements.
- A reversed function should be invertible
- A user should never see a 404 page through navigation
- The process should fail for non-premium users

Such requirements usually map one to one in code.

A test for a site navigation that never should 404 might look like this:
```kotlin:ank
import arrow.check.gen.Gen
class Page {
    fun navOptions(): Array<String> = TODO()
    fun navigate(vararg target: String): Page = TODO()
}
fun pageGen(): Gen<Page> = TODO()
val Page404: Page = Page()

//sampleStart
check {
    // Generate a random starting page
    val navStart = forAll(pageGen()).bind()
    // Generate a command given a set of possible options
    val navTarget = forAll { element(navStart.navOptions()) }.bind()
    // run test
    val resultPage = navStart.navigate(*navTarget)
    // verify properties
    // no 404 (This is just one out of many possible properties)
    resultPage.neqv(Page404).bind()
}
//sampleEnd
```

As an app grows larger more and more navigation options show up and verifying the correct behaviour with tests grows quickly.
However with this property based test, all navigation options are tested in just one test and the random generation ensures to test them all.

## Random data and readable output

Now one objection one might have to property based testing is that random data is not readable. By definiton that is.

To combat this property tests try to shrink the input on failure. What this means can be seen from this test:
```kotlin:ank:silent
import arrow.core.toT

//sampleStart
check {
    val (a, b) = forAllT { tupled(int(0..100), int(0..100)) }.bind()
    // obviously going to fail, but with what error?
    (a toT (b + 1)).eqv(a toT b).bind()
}
//sampleEnd
    .attempt().unsafeRunSync()
```
```
🞬 <interactive> failed after 1 test 2 shrinks.
forAll = (0, 0)
━━━ Failed (- lhs =/= + rhs) ━━━
 (
    0
- , 1
+ , 0
  )
```
Arrow check shows the number of total tests, how many times it had to shrink the test failure, the inputs and lastly a diff of the output.

> If you are in a terminal with colors enabled you will also see the offending lines rendered in red and green in the diff.

There is two things to notice:

- it shrunk the failure only twice. The reason for that is that every (ranged) generator has an implicit origin and a range for how far its input can lead. Arrow-check will always start with the origin as that is usually the smallest and thus fastest input.
- Nowhere in this test did we have to manually implement or define how the shrinker works. This is one of the major advantages over other property based testing libraries. Arrow-check can derive most shrinkers for free.

This shrinking approach extends to all properties and generators one can think of.
No matter how large a datatype is arrow-check will, if the generator is defined [properly], be able to shrink the input.

## Where property based testing can help

There are no silver bullets. Property based testing is no exception, it will not replace all unit tests and it will not cover all cases in complex systems.

It can however significantly reduce the amount of boilerplate tests while also providing the tester with inputs and edge cases that might otherwise go unnoticed.

Property based testing is yet another tool that can enhance the correctness guarantees tests offer.

If you are tired of writing manual test inputs, being caught by edge cases in that input and/or simply want to cover larger parts of the domain, then give property based testing a shot!

### What's next?
- [Getting started with arrow-check] A complete guide on how to setup arrow-check and define basic properties.
- [Why another property based testing library?] On what arrow-check specifically offers over other property based testing libraries.
