/**
 * Copyright (c) 2015, CodiLime, Inc.
 *
 * Owner: Wojciech Jurczyk
 */

package io.deepsense.experimentmanager.app.rest.json

import java.util.UUID

import spray.httpx.SprayJsonSupport
import spray.json._

import io.deepsense.experimentmanager.app.models.Graph.Node
import io.deepsense.experimentmanager.app.models.{Graph, Id, Experiment, InputExperiment}
import io.deepsense.experimentmanager.app.rest.actions.{AbortAction, Action, LaunchAction}

/**
 * Defines how models are serialized to JSON and deserialized from it.
 */
object RestJsonProtocol extends DefaultJsonProtocol with SprayJsonSupport {

  implicit object IdFormat extends RootJsonFormat[Id] {
    override def write(obj: Id) = JsString(obj.value.toString)

    override def read(json: JsValue): Experiment.Id = json match {
      case JsString(x) => Id(UUID.fromString(x))
      case x => deserializationError(s"Expected Id as UUID as JsString, but got $x")
    }
  }

  implicit val graphFormat = jsonFormat0(Graph.apply)
  implicit val experimentFormat = jsonFormat4(Experiment.apply)
  implicit val inputExperimentFormat = jsonFormat3(InputExperiment.apply)
  implicit val nodeFormat = jsonFormat1(Node.apply)
  implicit val abortActionFormat = jsonFormat1(AbortAction.apply)
  implicit val launchActionFormat = jsonFormat2(LaunchAction.apply)

  implicit object ActionJsonFormat extends RootJsonReader[Action] {
    val abortName = "abort"
    val launchName = "launch"
    override def read(json: JsValue): Action = json match {
      case JsObject(x) if x.contains(abortName) && x.size == 1 =>
        x.get(abortName).get.convertTo[AbortAction]
      case JsObject(x) if x.contains(launchName) && x.size == 1 =>
        x.get(launchName).get.convertTo[LaunchAction]
      case x => deserializationError(s"Expected Abort Action or Launch Action, but got $x")
    }
  }
}
