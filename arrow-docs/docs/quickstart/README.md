---
layout: docs-incubator
title: Arrow-check
permalink: /check/
---

Arrow-check is a property based testing library which aims to allow easy testing of functional programs.

Arrow-check works with and without the use of other arrow libraries, however it does provide a lot of convenience functions for working with arrow.

Use the list below to learn about how to effectively use arrow-check to test your programs:

- [Why property based testing?](/check/why-property-based-testing): A (short) primer as to why property based testing can help improve your tests and why it is a great fit for fp.
- [Getting started with arrow-check](/check/getting-started): Introduction to the libraries main functionality and a few basic examples.
- [Writing good generators]: A list of best practices when writing generators and pitfalls to avoid.
- [Testing IO/suspend]: On testing functions that perform effects.
- [Why another property based testing library?]: There are several other jvm libraries, why another one?
<!---
Stuff for later
- [Testing the untestable]: Testing complex systems with state machines
-->

## Setup

In order to use arrow-check you need two things: The library and a test-runner.
> At some point arrow-check might offer a custom test runner but for now one has to resort to third party libraries.

Currently arrow-check offers special support for [kotest] in the form of combinators that run properties inside kotest-tests.

The following gradle setup should get you started:
-- TODO