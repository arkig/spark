/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst

import org.apache.spark.util.Utils
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical.LocalRelation
import org.apache.spark.sql.types._

/**
 * A default version of ScalaReflection that uses the runtime universe.
 */
object ScalaReflection extends ScalaReflection {
  val universe: scala.reflect.runtime.universe.type = scala.reflect.runtime.universe
}

/**
 * Support for generating catalyst schemas for scala objects.
 */
trait ScalaReflection {
  /** The universe we work in (runtime or macro) */
  val universe: scala.reflect.api.Universe

  import universe._

  // The Predef.Map is scala.collection.immutable.Map.
  // Since the map values can be mutable, we explicitly import scala.collection.Map at here.
  import scala.collection.Map

  case class Schema(dataType: DataType, nullable: Boolean)

  /** Returns a Sequence of attributes for the given case class type. */
  def attributesFor[T: TypeTag]: Seq[Attribute] = schemaFor[T] match {
    case Schema(s: StructType, _) =>
      s.toAttributes
  }

  /** Returns a catalyst DataType and its nullability for the given Scala Type using reflection. */
  def schemaFor[T: TypeTag]: Schema =
    ScalaReflectionLock.synchronized { schemaFor(typeOf[T]) }

  /** Returns a catalyst DataType and its nullability for the given Scala Type using reflection. */
  def schemaFor(tpe: `Type`): Schema = ScalaReflectionLock.synchronized {
    val className: String = tpe.erasure.typeSymbol.asClass.fullName
    tpe match {
      case t if Utils.classIsLoadable(className) &&
        Utils.classForName(className).isAnnotationPresent(classOf[SQLUserDefinedType]) =>
        // Note: We check for classIsLoadable above since Utils.classForName uses Java reflection,
        //       whereas className is from Scala reflection.  This can make it hard to find classes
        //       in some cases, such as when a class is enclosed in an object (in which case
        //       Java appends a '$' to the object name but Scala does not).
        val udt = Utils.classForName(className)
          .getAnnotation(classOf[SQLUserDefinedType]).udt().newInstance()
        Schema(udt, nullable = true)
      case t if t <:< typeOf[Option[_]] =>
        val TypeRef(_, _, Seq(optType)) = t
        Schema(schemaFor(optType).dataType, nullable = true)
      // Need to decide if we actually need a special type here.
      case t if t <:< typeOf[Array[Byte]] => Schema(BinaryType, nullable = true)
      case t if t <:< typeOf[Array[_]] =>
        val TypeRef(_, _, Seq(elementType)) = t
        val Schema(dataType, nullable) = schemaFor(elementType)
        Schema(ArrayType(dataType, containsNull = nullable), nullable = true)
      case t if t <:< typeOf[Seq[_]] =>
        val TypeRef(_, _, Seq(elementType)) = t
        val Schema(dataType, nullable) = schemaFor(elementType)
        Schema(ArrayType(dataType, containsNull = nullable), nullable = true)
      case t if t <:< typeOf[Map[_, _]] =>
        val TypeRef(_, _, Seq(keyType, valueType)) = t
        val Schema(valueDataType, valueNullable) = schemaFor(valueType)
        Schema(MapType(schemaFor(keyType).dataType,
          valueDataType, valueContainsNull = valueNullable), nullable = true)
      case t if t <:< typeOf[Product] =>
        val formalTypeArgs = t.typeSymbol.asClass.typeParams
        val TypeRef(_, _, actualTypeArgs) = t
        val constructorSymbol = t.member(nme.CONSTRUCTOR)
        val params = if (constructorSymbol.isMethod) {
          constructorSymbol.asMethod.paramss
        } else {
          // Find the primary constructor, and use its parameter ordering.
          val primaryConstructorSymbol: Option[Symbol] = constructorSymbol.asTerm.alternatives.find(
            s => s.isMethod && s.asMethod.isPrimaryConstructor)
          if (primaryConstructorSymbol.isEmpty) {
            sys.error("Internal SQL error: Product object did not have a primary constructor.")
          } else {
            primaryConstructorSymbol.get.asMethod.paramss
          }
        }
        Schema(StructType(
          params.head.map { p =>
            val Schema(dataType, nullable) =
              schemaFor(p.typeSignature.substituteTypes(formalTypeArgs, actualTypeArgs))
            StructField(p.name.toString, dataType, nullable)
          }), nullable = true)
      case t if t <:< typeOf[String] => Schema(StringType, nullable = true)
      case t if t <:< typeOf[java.sql.Timestamp] => Schema(TimestampType, nullable = true)
      case t if t <:< typeOf[java.sql.Date] => Schema(DateType, nullable = true)
      case t if t <:< typeOf[BigDecimal] => Schema(DecimalType.Unlimited, nullable = true)
      case t if t <:< typeOf[java.math.BigDecimal] => Schema(DecimalType.Unlimited, nullable = true)
      case t if t <:< typeOf[Decimal] => Schema(DecimalType.Unlimited, nullable = true)
      case t if t <:< typeOf[java.lang.Integer] => Schema(IntegerType, nullable = true)
      case t if t <:< typeOf[java.lang.Long] => Schema(LongType, nullable = true)
      case t if t <:< typeOf[java.lang.Double] => Schema(DoubleType, nullable = true)
      case t if t <:< typeOf[java.lang.Float] => Schema(FloatType, nullable = true)
      case t if t <:< typeOf[java.lang.Short] => Schema(ShortType, nullable = true)
      case t if t <:< typeOf[java.lang.Byte] => Schema(ByteType, nullable = true)
      case t if t <:< typeOf[java.lang.Boolean] => Schema(BooleanType, nullable = true)
      case t if t <:< definitions.IntTpe => Schema(IntegerType, nullable = false)
      case t if t <:< definitions.LongTpe => Schema(LongType, nullable = false)
      case t if t <:< definitions.DoubleTpe => Schema(DoubleType, nullable = false)
      case t if t <:< definitions.FloatTpe => Schema(FloatType, nullable = false)
      case t if t <:< definitions.ShortTpe => Schema(ShortType, nullable = false)
      case t if t <:< definitions.ByteTpe => Schema(ByteType, nullable = false)
      case t if t <:< definitions.BooleanTpe => Schema(BooleanType, nullable = false)
      case other =>
        throw new UnsupportedOperationException(s"Schema for type $other is not supported")
    }
  }

  def typeOfObject: PartialFunction[Any, DataType] = {
    // The data type can be determined without ambiguity.
    case obj: Boolean => BooleanType
    case obj: Array[Byte] => BinaryType
    case obj: String => StringType
    case obj: UTF8String => StringType
    case obj: Byte => ByteType
    case obj: Short => ShortType
    case obj: Int => IntegerType
    case obj: Long => LongType
    case obj: Float => FloatType
    case obj: Double => DoubleType
    case obj: java.sql.Date => DateType
    case obj: java.math.BigDecimal => DecimalType.Unlimited
    case obj: Decimal => DecimalType.Unlimited
    case obj: java.sql.Timestamp => TimestampType
    case null => NullType
    // For other cases, there is no obvious mapping from the type of the given object to a
    // Catalyst data type. A user should provide his/her specific rules
    // (in a user-defined PartialFunction) to infer the Catalyst data type for other types of
    // objects and then compose the user-defined PartialFunction with this one.
  }

  implicit class CaseClassRelation[A <: Product : TypeTag](data: Seq[A]) {

    /**
     * Implicitly added to Sequences of case class objects.  Returns a catalyst logical relation
     * for the the data in the sequence.
     */
    def asRelation: LocalRelation = {
      val output = attributesFor[A]
      LocalRelation.fromProduct(output, data)
    }
  }
}
