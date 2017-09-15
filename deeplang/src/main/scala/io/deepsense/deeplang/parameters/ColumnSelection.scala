/**
 * Copyright (c) 2015, CodiLime, Inc.
 *
 * Owner: Witold Jedrzejewski
 */

package io.deepsense.deeplang.parameters

import spray.json.DefaultJsonProtocol._
import spray.json._

import io.deepsense.deeplang.parameters.ColumnRole._
import io.deepsense.deeplang.parameters.ColumnType._

/**
 * Represents selecting subset of columns of dataframe.
 */
@SerialVersionUID(1)
sealed abstract class ColumnSelection(
    typeName: String)
  extends Serializable {

  final def toJson: JsValue = JsObject(
    ColumnSelection.typeField -> typeName.toJson,
    ColumnSelection.valuesField -> valuesToJson)

  protected def valuesToJson: JsValue
}

object ColumnSelection {
  val typeField = "type"

  val valuesField = "values"

  def fromJson(jsValue: JsValue): ColumnSelection = jsValue match {
    case JsObject(map) =>
      val value = map(valuesField)
      map(typeField) match {
        case JsString(NameColumnSelection.typeName) =>
          NameColumnSelection.fromJson(value)
        case JsString(IndexColumnSelection.typeName) =>
          IndexColumnSelection.fromJson(value)
        case JsString(TypeColumnSelection.typeName) =>
          TypeColumnSelection.fromJson(value)
        case unknownType =>
          throw new DeserializationException(s"Cannot create column selection with $jsValue:" +
            s"unknown type $unknownType")
      }
    case _ => throw new DeserializationException(
      s"Cannot create column selection with $jsValue: object expected.")
  }
}

/**
 * Represents selecting subset of columns which have one of given names.
 */
case class NameColumnSelection(names: Set[String])
  extends ColumnSelection(NameColumnSelection.typeName) {

  override protected def valuesToJson: JsValue = names.toJson
}

object NameColumnSelection {
  val typeName = "columnList"

  def fromJson(jsValue: JsValue): NameColumnSelection = {
    NameColumnSelection(jsValue.convertTo[Set[String]])
  }
}
/**
 * Represents selecting subset of columns which have one of given indexes.
 */
case class IndexColumnSelection(indexes: Set[Int])
  extends ColumnSelection(IndexColumnSelection.typeName) {

  override protected def valuesToJson: JsValue = indexes.toJson
}

object IndexColumnSelection {
  val typeName = "indexList"

  def fromJson(jsValue: JsValue): IndexColumnSelection = {
    IndexColumnSelection(jsValue.convertTo[Set[Int]])
  }
}

/**
 * Represents selecting subset of columns which have one of given types.
 */
case class TypeColumnSelection(types: Set[ColumnType])
  extends ColumnSelection(TypeColumnSelection.typeName) {

  override protected def valuesToJson: JsValue = types.map(_.toString).toJson

}

object TypeColumnSelection {
  val typeName = "typeList"

  def fromJson(jsValue: JsValue): TypeColumnSelection = {
    TypeColumnSelection(jsValue.convertTo[Set[String]].map(ColumnType.withName))
  }
}