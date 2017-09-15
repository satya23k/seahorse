/**
 * Copyright (c) 2015, CodiLime, Inc.
 *
 * Owner: Rafal Hryciuk
 */

package io.deepsense.deeplang

import org.scalatest._
import org.scalatest.mock.MockitoSugar

/**
 * Base class for unit tests as advised: http://www.scalatest.org/user_guide/defining_base_classes
 */
abstract class UnitSpec
  extends FlatSpec
  with Matchers
  with OptionValues
  with Inside
  with Inspectors
  with MockitoSugar