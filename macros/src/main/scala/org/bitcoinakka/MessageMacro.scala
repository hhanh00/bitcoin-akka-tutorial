package org.bitcoinakka

import java.time.Instant

import scala.language.experimental.macros

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.reflect.macros.whitebox.Context


@compileTimeOnly("enable macro paradise to expand macro annotations")
class MessageMacro extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro MessageMacro.impl
}

object MessageMacro {
  val listOf = "List\\[(\\w+)\\]".r

  def impl(c: Context)(annottees: c.Expr[Any]*): c.Expr[Any] = {
    import c.universe._

    def getField(tpe: String) = {
      tpe match {
        case "Byte" => q"bi.getByte"
        case "Int" => q"bi.getInt"
        case "Long" => q"bi.getLong"
        case "BigInt" => q"bi.getBigInt"
        case "Hash" => q"bi.getHash"
        case "String" => q"bi.getVarString"
        case "Script" => q"bi.getScript"
        case "BlockHeader" => q"bi.getBlockHeader"
        case "Instant" => q"Instant.ofEpochSecond(bi.getInt)"
        case "InetSocketAddress" => q"bi.getInetSocketAddress"
        case m => q"${TermName(m)}.parseBI(bi)"
      }
    }

    def readScalar(name: TermName, tpe: String): c.universe.Tree = q"val $name = ${getField(tpe)}"
    def readList(name: TermName, tpe: String) = {
      q"""val $name = {
          val c = bi.getVarInt
          (for (_ <- 0 until c) yield ${getField(tpe)}).toList
          }"""
    }

    def putField(name: TermName, tpe: String) = tpe match {
      case "Byte" => q"bb.putByte($name)"
      case "Int" => q"bb.putInt($name)"
      case "Long" => q"bb.putLong($name)"
      case "BigInt" => q"bb.putBigInt($name)"
      case "Hash" => q"bb.putBytes($name)"
      case "String" => q"bb.putVarString($name)"
      case "Script" => q"bb.putScript($name)"
      case "BlockHeader" => q"bb.putBlockHeader($name)"
      case "Instant" => q"bb.putInt($name.getEpochSecond.toInt)"
      case "InetSocketAddress" => q"bb.putInetSocketAddress($name)"
      case m => q"bb.append($name.toByteString())"
    }

    def writeList(name: TermName, tpe: String) = {
      q"""{ bb.putVarInt($name.length); $name.foreach { n => ${putField(TermName("n"), tpe)} } }"""
    }

    def modifyClass(classDef: ClassDef): c.Expr[Any] = {
      val (messageName: TypeName, fields: List[ValDef]) =
      try {
        val q"case class $messageName(..$fields)" = classDef
        (messageName, fields)
      }
      catch {
        case m: MatchError => c.abort(c.enclosingPosition, s"Must use @MessageMacro on a case class, ${m}")
      }

      val readBody = {
        for {
          field: ValDef <- fields
        } yield {
          val fieldType = field.tpt.toString()
          val name = field.name
          fieldType match {
            case listOf(tpe) => readList(name, tpe)
            case tpe => readScalar(name, tpe)
          }
        }
      }

      val writeBody = {
        for {
          field: ValDef <- fields
        } yield {
          val fieldType = field.tpt.toString()
          val name = field.name
          fieldType match {
            case listOf(tpe) => writeList(name, tpe)
            case tpe => putField(name, tpe)
          }
        }
      }

      val messageNameLC = messageName.toString.toLowerCase
      val fieldNames = fields.map(_.name)

      c.Expr(q"""case class $messageName(..$fields) extends BitcoinMessage {
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
      )
    }

    annottees.map(_.tree) match {
      case (classDef: ClassDef) :: Nil => modifyClass(classDef)
      case _ => c.abort(c.enclosingPosition, "Cannot use @MessageMacro on this symbol")
    }
  }
}
