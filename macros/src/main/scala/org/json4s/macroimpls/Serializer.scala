package org.json4s.macroimpls

import language.experimental.macros
import scala.reflect.macros.Context
import java.util.Date

import org.json4s.{Formats, JsonWriter, JValue, JObject}
import macrohelpers._


// Intended to be the serialization side of the class builder
object Serializer {
  
  type Writer = JsonWriter[_]

  // Makes the code generated by the macros significantly less cumbersome
  private[this] class WriterStack(var current: Writer) {
    def startArray() = { current = current.startArray() }
    def endArray() = { current = current.endArray() }
    def startObject() = { current = current.startObject() }
    def endObject() = { current = current.endObject() }

    def int(in: Int) = { current = current.int(in) }
    def string(in: String) = { current = current.string(in) }
    def float(in: Float) = { current = current.float(in) }
    def double(in: Double) = { current = current.double(in) }
    def bigDecimal(in: BigDecimal) = { current = current.bigDecimal(in) }
    def short(in: Short) = { current = current.short(in) }
    def bigInt(in: BigInt) = { current = current.bigInt(in) }
    def byte(in: Byte) = { current = current.byte(in) }
    def long(in: Long) = { current = current.long(in) }
    def boolean(in: Boolean) = { current = current.boolean(in) }

    def startField(name: String) = { current = current.startField(name) }
    def addJValue(jv: JValue) = { current = current.addJValue(jv) }

    def result = current.result
  }

  /* ----------------- Offers directly to object serialization -------------------------- */
  def serializeToObject[U](obj: U)(implicit defaultFormats: Formats) = macro implSerToObj[U]
  def implSerToObj[U: c.WeakTypeTag](c: Context)(obj: c.Expr[U])(defaultFormats: c.Expr[Formats]): c.Expr[JObject] = {
    import c.universe._

    reify {
      val writer = JsonWriter.ast

      {impl(c)(obj, c.Expr[String]{Literal(Constant("tmpname"))}, c.Expr[Writer](Ident("writer")))(defaultFormats)}.splice
      val jobj: JObject = writer.result.asInstanceOf[JObject]
      val JObject(("tmpname", result)::Nil) = jobj // Now just extract the object from the wrapper
      result.asInstanceOf[JObject]
    }
  }

  /* ----------------- Macro Serializer ----------------- */
  def serialize[U](obj: U, name: String, writer: Writer)(implicit defaultFormats: Formats) = macro impl[U]
  def impl[U: c.WeakTypeTag](c: Context)(obj: c.Expr[U], name: c.Expr[String], writer: c.Expr[Writer])
                           (defaultFormats: c.Expr[Formats]): c.Expr[Unit] = {
                      
    import c.universe._
    val helpers = new macrohelpers.MacroHelpers[c.type](c)
    import helpers._
    
    // Will help manage the JsonWriters for us instead of having to
    // keep track as we go down the tree
    val Block(writerStackDef::Nil, _) = reify{
      val writerStack = new WriterStack(writer.splice)
    }.tree

    val writerStack = c.Expr[WriterStack](Ident("writerStack"))

    def dateExpr(t: Tree) = reify{
      writerStack.splice.string(defaultFormats.splice.dateFormat.format(c.Expr[Date](t).splice))
    }

    def symbolExpr(t: Tree) = reify{writerStack.splice.string(c.Expr[scala.Symbol](t).splice.name)}

    val primitiveTypes =
             (typeOf[Int], (t: Tree) => reify{writerStack.splice.int(c.Expr[Int](t).splice)})::
             (typeOf[String], (t: Tree) => reify{writerStack.splice.string(c.Expr[String](t).splice)})::
             (typeOf[Float], (t: Tree) => reify{writerStack.splice.float(c.Expr[Float](t).splice)})::
             (typeOf[Double], (t: Tree) => reify{writerStack.splice.double(c.Expr[Double](t).splice)})::
             (typeOf[Boolean], (t: Tree) => reify{writerStack.splice.boolean(c.Expr[Boolean](t).splice)})::
             (typeOf[Long], (t: Tree) => reify{writerStack.splice.long(c.Expr[Long](t).splice)})::
             (typeOf[Byte], (t: Tree) => reify{writerStack.splice.byte(c.Expr[Byte](t).splice)})::
             (typeOf[BigInt], (t: Tree) => reify{writerStack.splice.bigInt(c.Expr[BigInt](t).splice)})::
             (typeOf[Short], (t: Tree) => reify{writerStack.splice.short(c.Expr[Short](t).splice)})::
             (typeOf[BigDecimal], (t: Tree) => reify{writerStack.splice.bigDecimal(c.Expr[BigDecimal](t).splice)})::
             Nil
    
    
    // Assumes that you are already in an object or list

    // TODO: This needs to be inverted so that it assumes that you have a complex object, and wants to serialize the fields.
    def dumpObject(oldTpe: Type, path: Tree, isList: Boolean=false): c.Tree = {

      val TypeRef(_, sym: Symbol, tpeArgs: List[Type]) = oldTpe
      // get fields
      val fields = getVars(oldTpe):::getVals(oldTpe)

      // The mapping method to build all the trees ///////////////////////////////////////
      val fieldTrees = fields.map{ pSym =>

        val TypeRef(_, sym: Symbol, tpeArgs: List[Type]) = oldTpe
        val tpe = pSym.typeSignature.substituteTypes(sym.asClass.typeParams, tpeArgs)

        val fieldName = pSym.name.decoded.trim    // Do I need to trim here?
        val fieldPath = Select(path, newTermName(fieldName))

        val startFieldExpr = if(isList) {
          reify{}
        } else reify{writerStack.splice.startField(LIT(fieldName).splice)}

        val fieldTree: c.Tree = if (primitiveTypes.exists(_._1 =:= tpe)) { // Must be primitive
        val expr = primitiveTypes.find(_._1 =:= tpe).get._2
          reify{
            expr(fieldPath).splice  //writerStack.splice.primative(c.Expr(path).splice)
          }.tree
        }

        else if (tpe =:= typeOf[scala.Symbol]) { symbolExpr(fieldPath).tree }


        else if (tpe =:= typeOf[Date]) {
          reify{
            dateExpr(fieldPath).splice
          }.tree
        }
        // Handle the lists
        else if(tpe <:< typeOf[scala.collection.Seq[Any]]) {
          val TypeRef(_, sym:Symbol, pTpe::Nil) = tpe
          reify{
            writerStack.splice.startArray()
            c.Expr[scala.collection.Seq[Any]](path).splice.foreach { i =>
              c.Expr(dumpObject(pTpe, Ident("i"), isList=true)).splice
            }
            writerStack.splice.endArray()
          }.tree
        }

        else if(tpe <:< typeOf[scala.collection.GenMap[Any, Any]].erasure) {
          val TypeRef(_, _, keyTpe::valTpe::Nil) = tpe

          if(!primitiveTypes.exists(_._1 =:= keyTpe)) {
            c.abort(c.enclosingPosition,
              s"Maps nees to have keys of primative type! Type: $keyTpe")
          }
          val kExpr = c.Expr[String](Ident("kstr"))
          reify{
            writerStack.splice.startObject()
            c.Expr[scala.collection.GenMap[Any, Any]](path).splice.foreach { case (k, v) =>
              val kstr = k.toString
              c.Expr(dumpObject(valTpe, kExpr.tree)).splice
            }
            writerStack.splice.endObject()

          }.tree

          // Handle Options
        } else if(tpe <:< typeOf[Option[Any]]) {
          val TypeRef(_, _ :Symbol, pTpe::Nil) = tpe
          reify{
            // I would be happier if I could to c.Expr[Option["real type"]]
            // but this seems to work. I'm not sure if its just for type
            // checking in reify or what...
            PrimativeHelpers.optIdent(c.Expr[Option[Any]](fieldPath).splice) match {
              case Some(x) => c.Expr[Unit](dumpObject(pTpe, Ident("x"))).splice
              case None    => {
                writerStack.splice.addJValue(org.json4s.JNothing)
              }
            }
          }.tree
        }

        else {  // Complex object
//        val TypeRef(_, sym: Symbol, tpeArgs: List[Type]) = tpe
//          // get fields
//          val fields = getVars(tpe):::getVals(tpe)
//          val fieldTrees = fields map { pSym =>
//            val pTpe = pSym.typeSignature.substituteTypes(sym.asClass.typeParams, tpeArgs)
//            val fieldName = pSym.name.decoded.trim    // Do I need to trim here?
//            val fieldPath = Select(path, newTermName(fieldName))
//            dumpObject(pTpe, fieldPath)
//          }
//
//          // Return add all the blocks for each field and pop this obj off the stack
//          Block(
//            reify{
//              writerStack.splice.startObject()
//            }.tree::fieldTrees,
//            reify{writerStack.splice.endObject()}.tree)
//        }
          dumpObject(tpe, fieldPath)
        } // dumpObject map
        Block(startFieldExpr.tree, fieldTree)
      }

      // Return add all the blocks for each field and pop this obj off the stack
      Block(
        reify{
          //startFieldExpr.splice
          writerStack.splice.startObject()
        }.tree::fieldTrees:::
        reify{writerStack.splice.endObject()}.tree::Nil: _*)
      
//      if (primitiveTypes.exists(_._1 =:= tpe)) { // Must be primitive
//        val expr = primitiveTypes.find(_._1 =:= tpe).get._2
//        reify{
//          startFieldExpr.splice
//          expr(path).splice  //writerStack.splice.primative(c.Expr(path).splice)
//        }.tree
//      }
//
//      else if (tpe =:= typeOf[scala.Symbol]) {
//        reify {
//          startFieldExpr.splice
//          symbolExpr(path).splice
//        }.tree
//      }
//
//      else if (tpe =:= typeOf[Date]) {
//        reify{
//          startFieldExpr.splice
//          dateExpr(path).splice
//        }.tree
//      }
//      // Handle the lists
//      else if(tpe <:< typeOf[scala.collection.Seq[Any]]) {
//        val TypeRef(_, sym:Symbol, pTpe::Nil) = tpe
//        reify{
//          startFieldExpr.splice
//          writerStack.splice.startArray()
//          c.Expr[scala.collection.Seq[Any]](path).splice.foreach { i =>
//            c.Expr(dumpObject(pTpe, Ident("i"), LIT(""), isList=true)).splice
//          }
//          writerStack.splice.endArray()
//        }.tree
//      }
//
//      else if(tpe <:< typeOf[scala.collection.GenMap[Any, Any]].erasure) {
//        val TypeRef(_, _, keyTpe::valTpe::Nil) = tpe
//
//        if(!primitiveTypes.exists(_._1 =:= keyTpe)) {
//          c.abort(c.enclosingPosition,
//            s"Maps nees to have keys of primative type! Type: $keyTpe")
//        }
//        val kExpr = c.Expr[String](Ident("kstr"))
//        reify{
//          startFieldExpr.splice
//          writerStack.splice.startObject()
//          c.Expr[scala.collection.GenMap[Any, Any]](path).splice.foreach { case (k, v) =>
//            val kstr = k.toString
//            c.Expr(dumpObject(valTpe, Ident("v"), kExpr)).splice
//          }
//          writerStack.splice.endObject()
//
//        }.tree
//
//      // Handle Options
//      } else if(tpe <:< typeOf[Option[Any]]) {
//        val TypeRef(_, _ :Symbol, pTpe::Nil) = tpe
//        reify{
//        // I would be happier if I could to c.Expr[Option["real type"]]
//        // but this seems to work. I'm not sure if its just for type
//        // checking in reify or what...
//          PrimativeHelpers.optIdent(c.Expr[Option[Any]](path).splice) match {
//            case Some(x) => c.Expr[Unit](dumpObject(pTpe, Ident("x"), name)).splice
//            case None    => {
//              startFieldExpr.splice
//              writerStack.splice.addJValue(org.json4s.JNothing)
//            }
//          }
//        }.tree
//      }
//
//      else {  // Complex object
//        val TypeRef(_, sym: Symbol, tpeArgs: List[Type]) = tpe
//        // get fields
//        val fields = getVars(tpe):::getVals(tpe)
//        val fieldTrees = fields map { pSym =>
//          val pTpe = pSym.typeSignature.substituteTypes(sym.asClass.typeParams, tpeArgs)
//          val fieldName = pSym.name.decoded.trim    // Do I need to trim here?
//          val fieldPath = Select(path, newTermName(fieldName))
//          dumpObject(pTpe, fieldPath, LIT(fieldName))
//        }
//
//        // Return add all the blocks for each field and pop this obj off the stack
//        Block(
//        reify{
//          //startFieldExpr.splice
//          writerStack.splice.startObject()
//        }.tree::fieldTrees,
//        reify{writerStack.splice.endObject()}.tree)
//      }
    } // dumpObject
    
    val code = Block(
      writerStackDef::
      //reify( writerStack.splice.startObject()).tree::
      dumpObject(weakTypeOf[U], obj.tree)::Nil,
      //reify(writerStack.splice.endObject()).tree::Nil,
      c.literalUnit.tree
    )
    println(s"------------------ Debug: Generated Code ------------------\n $code")
    c.Expr[Unit](code)
  }
  
}
