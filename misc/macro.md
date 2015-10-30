---
layout: page
sha: 90aeca2d4a1dbc59a8ed05939a17d861ffa63510
---

# Message Serializer Generation

This commit changes the project structure so you need to either recreate your IDEA project or refresh it. 

Scala Macro is a technique by which we can hook into one of the phases of compilation and modify the syntax tree before it is turned into
code.

This idea is extensively used in LISP which has the benefit of being [homoiconic][1]. A LISP program is itself a list and can be manipulated
by the program using its extensive list operators.

Scala doesn't have this advantage so macros are a bit harder to use but the idea is the same. We define a macro annotation by

```scala
@compileTimeOnly("enable macro paradise to expand macro annotations")
class MessageMacro extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro MessageMacro.impl
}
```

The `@compileTimeOnly` is there to ensure that the macro is properly expanded. If the project isn't properly setup to use macros, 
the `@MessageMacro` is a normal annotation and would have no effect. `@compileTimeOnly` emits a compile-time error because the
`@MessageMacro` should have been expanded.

The `macro` keyword introduces the function that receives the [AST][2] and will transform it.

Let's say we use it on `Ping`.

```scala
@MessageMacro case class Ping(nonce: Long)
```

The signature of the macro implementation is:

```scala
object MessageMacro {
  def impl(c: Context)(annottees: c.Expr[Any]*): c.Expr[Any] = ???
``` 

It receives a compiler context and a list of annottees that correspond to the annotated items. In this case the case class `Ping`.
The result should be a transformed `Expr` tree. Now, it's possible to directly work with the trees but because Scala is a fairly
complex language, the AST is quite complex too.

There is an easier way to manipulate trees called [quasi-quoting][3] coming from LISP.

In this example where `classDef` is an expression that matches a class definition

```scala
val q"case class $messageName(..$fields)" = classDef
```

The `q` prefixing the string marks the quasi-quote that is pattern matched against the class definition. If it matches, the variable
`messageName` is bound to the sub tree that represents the class name and `$fields` is bound to a list of fields.

When the quasi-quote is used on the right side of a statement, it builds an expression by inserting the values of the quoted
variables.

For example, this builds the output expressions

```scala
q"""case class $messageName(..$fields) extends BitcoinMessage {
  import BitcoinMessage.ByteStringBuilderExt
  val command = $messageNameLC
  def toByteString(): ByteString = {
    val bb = new ByteStringBuilder
    ..$writeBody
    bb.result()
  }
}

object ${messageName.toTermName} extends ByteOrderImplicit {
  import BitcoinMessage.ByteStringIteratorExt
  def parse(bs: ByteString) = parseBI(bs.iterator)
  def parseBI(bi: ByteIterator) = {
    ..$readBody
    new $messageName(..$fieldNames)
  }
}"""
```

quasi-quoting is a very powerful feature and greatly simplifies working with ASTs but there are still times where we have to work
with the trees directly. The `scala-reflect` package has the API for the AST data model and provides many manipulations and
transformations functions.

[1]: https://en.wikipedia.org/wiki/Homoiconicity
[2]: https://en.wikipedia.org/wiki/Abstract\_syntax_tree
[3]: https://en.wikipedia.org/wiki/Lisp\_\(programming_language\)#Self-evaluating\_forms\_and\_quoting


