---
layout: page
sha: 8d14eb80744d20d1384076ebe47d200ba6b60cc8
---

# Message Serializer Generation

This commit changes the project structure so you need to either recreate your IDEA project or refresh it. 

Scala Macro is a technique by which we can hook into one of the phases of compilation and modify the syntax tree before it is turned into
code.

This idea is extensively used in LISP which has the benefit of being [homoiconic][1]. A LISP program is itself a list and can be manipulated
by the program using its extensive list operators.

Scala doesn't have this advantage so macros are a bit harder to use but the idea is the same. We define a macro annotation.

[1]: https://en.wikipedia.org/wiki/Homoiconicity


