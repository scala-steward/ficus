package net.ceedubs.ficus.readers

import com.typesafe.config.Config
import scala.reflect.internal.{StdNames, SymbolTable, Definitions}
import scala.language.experimental.macros

trait CompanionApplyReader {
  def companionApplyValueReader[T]: ValueReader[T] = macro CompanionApplyReaderMacros.companionApplyValueReader[T]
}

object CompanionApplyReader extends CompanionApplyReader

object CompanionApplyReaderMacros {
  import scala.reflect.macros.Context

  def companionApplyValueReader[T : c.WeakTypeTag](c: Context): c.Expr[ValueReader[T]] = {
    import c.universe._

    reify {
      new ValueReader[T] {
        def read(config: Config, path: String): T = instantiateFromConfig[T](c)(
          config = c.Expr[Config](Ident(newTermName("config"))),
          path = c.Expr[String](Ident(newTermName("path")))).splice
      }
    }
  }

  def instantiateFromConfig[T : c.WeakTypeTag](c: Context)(config: c.Expr[Config], path: c.Expr[String]): c.Expr[T] = {
    import c.universe._

    val tpe = weakTypeOf[T]
    val applySymbol = tpe.typeSymbol.companionSymbol.typeSignature.member(newTermName("apply"))
    val applyMethod = applySymbol.asMethod
    val applyArgs = applyMethod.paramss.head.zipWithIndex map { case (param, index) =>
      val name = param.name.decoded
      val nameExpr = c.literal(name)
      val returnType: Type = param.typeSignatureIn(tpe)
      val key = reify(path.splice + "." + nameExpr.splice)

      val readerType = appliedType(weakTypeOf[ValueReader[_]].typeConstructor, List(returnType))
      val reader = c.inferImplicitValue(readerType, silent = true) match {
        case EmptyTree =>
          c.abort(c.enclosingPosition,
            s"An implicit value reader of type $readerType must be in scope to read parameter '$name' on 'apply' method of object $tpe")
        case x => x
      }

      if (param.asTerm.isParamWithDefault) {
        val optionReader = Apply(Select(reify(OptionReader).tree, newTermName("optionValueReader")), List(reader))
        val argValueMaybe = readConfigValue(c)(config, key, optionReader)
        Apply(Select(argValueMaybe.tree, newTermName("getOrElse")), List({
          // fall back to default value for param
          val u = c.universe.asInstanceOf[Definitions with SymbolTable with StdNames]
          val getter = u.nme.defaultGetterName(u.newTermName("apply"), index + 1)
          Select(Ident(tpe.typeSymbol.companionSymbol), newTermName(getter.encoded))
        }))
      } else {
        val argValue = readConfigValue(c)(config, key, reader)
        argValue.tree
      }
    }

    val tApply = Select(
      Ident(tpe.typeSymbol.companionSymbol),
      newTermName("apply"))
    c.Expr[T](Apply(tApply, applyArgs))
  }

  def readConfigValue[T: c.universe.WeakTypeTag](c: Context)(config: c.Expr[Config], path: c.Expr[String], reader: c.Tree): c.Expr[T] = {
    import c.universe._
    val readerRead = Select(reader, newTermName("read"))
    c.Expr[T](Apply(readerRead, List(config.tree, path.tree)))
  }
}