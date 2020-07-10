---
layout: docs-incubator
title: Getting started with arrow-check
permalink: /check/getting-started
---

# Getting started with arrow-check

## Gradle setup

TODO

## A basic test

We are going to start off with a basic example of using arrow-check + kotest to create and run property based tests.

The *arrow-check-kotest* module provides a dsl for building up test specs called `PropertySpec`:
```kotlin:ank
import arrow.check.PropertySpec
//sampleStart
class MyTest : PropertySpec({                           // 1
  "myTest" {                                            // 2
    
    val (a, bc) = forAll {                              // 3
      tupled(
        int(-100..100),
        tupled(int(-100..100), int(-100..100))
      )
    }.bind()

    val (b, c) = bc

    ((a + b) + c).eqv(a + (b + c)).bind()               // 4
  }
})
//sampleEnd
```

> Defining single tests like this is not the only way. You can learn more about the different ways you can define tests using a `PropertySpec` [here].

Let's take this in step by step:
- **1:** First of we define our test class very similar to other tests written with kotest by using a `PropertySpec` and passing it a function as a parameter.
In this function we have access to a dsl that allows defining a property test or groups of properties to test.
- **2:** Since this is only a basic example we will resort to defining a single named test.
This is done using the overloaded `String.invoke` to provide a nice dsl. Inside this function we have access to all the functions needed to define properties.
- **3:** Most property tests start by defining the set of inputs. Here we use `forAll` with a function which provides access to a dsl for building generators.
Inside the `forAll` we are using `tupled` to combine the three generators produced by `int(range)`.
*Note: we do have to call `bind()` at the end of `forAll` to apply the generator and access its produced values. If you forget `bind()` this should result in a type error*
- **4:** Now that we have our inputs we can test our assumptions. Here we are testing if addition is commutative.
We are testing for equality using `eqv`. `eqv` will compare the two arguments using `==` and also provide a git-like diff should the check fail.
*Note: As with `forAll` `eqv` also has to be specially applied to the context using `bind()`*

> If you want to learn more about how the dsl's work and why `bind` is necessary you can head over [here].

## Analysing test failure

When a property test fails it can lead to a bit of noise as it outputs quite a few things.
Let's have a look at what *arrow-check* outputs after a failed test.

We will be testing a function that supposedly copies a `User`:
```kotlin:ank:silent
import arrow.check.check
import arrow.check.gen.Gen
import arrow.check.gen.monadGen

//sampleStart
data class User(val name: String, val age: Int, val email: String) {
    fun shadyCopy(): User = User(name, age + 1, email)
}

// Some sort of sophisticated email gen
fun emailGen(): Gen<String> = Gen.monadGen { element("myEmail@provider.en") }

check {
  val user = forAll {
    mapN(
      ascii().string(2..100),
      int(18..100),
      emailGen()
    ) { (name, age, email) -> User(name, age, email) }
  }.bind()
   
  val copy = user.shadyCopy()
  copy.eqv(user).bind()  
}
//sampleEnd
    .attempt().unsafeRunSync()
``` 
```
🞬 <interactive> failed after 1 test 74 shrinks.
forAll =
  User(name = , age = 18, email = myEmail@provider.en)
━━━ Failed (- lhs =/= + rhs) ━━━
 User(
        name  = 
-     , age   = 19
+               18
      , email = myEmail@provider.en
      )

```
The first thing *arrow-check* outputs is the number of tests it ran and the number of times it tried to shrink the value.

After that we get every input that was generated, as with all values *arrow-check* tries to prettify any output that comes from `toString` methods.

Lastly we have a failure reason and, since we used `eqv`, we are getting a diff showing exactly where our equality check failed.

Another interesting part is what *arrow-check* shrunk the values to:
- name is shrunk to two ascii chars 0
- age is shrunk to 18 which is the lowest value we allowed during generation
- email isn't shrunk at all because it comes from a constant.

Likewise had we encoded more invariants into our data, not just the ranges, our data would still maintain all invariants during shrinking.

### Annotations

The output of *arrow-check* is not limited to inputs and failure. Using the annotate function we can add any sort of output as well.

This following test demonstrates this behavior:
```kotlin:ank:silent
import pretty.text

//sampleStart
check {
  annotate { "Annotation 1".text() }.bind()
  footnote { "Footnote".text() }.bind()
  annotate { "Annotation 2".text() }.bind()
  failWith("Failure").bind()
}
//sampleEnd
  .attempt().unsafeRunSync()
```
```
🞬 <interactive> failed after 1 test 0 shrinks.
Annotation 1
Annotation 2
Footnote
Failure
```

Both `annotate` and `footnote` take functions instead of values as arguments. The reason for that is because they are supposed to be lazy.
You can construct annotations with almost no cost on every test and it will only be forced if the test actually fails and is printed out.

They also don't accept a bare string. Instead they take a `Doc<Markup>`.
This allows users to make use of *arrow-check*s prettyprinting. This means we can add rich text with colors, icons and others without worrying with layout.
> Printing strings only is also fine just call the `String.text()` extension to turn any String to a `Doc`

> If you want to learn more about how the prettyprinter works in *arrow-check* check this [guide].

## 
