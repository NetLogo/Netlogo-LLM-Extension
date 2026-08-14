// ABOUTME: Converts parsed JSON into NetLogo values, mapping objects to [key value] pair lists
// ABOUTME: Also provides the key lookup that backs the llm:get primitive
package org.nlogo.extensions.llm.utils

import org.nlogo.core.LogoList

/**
 * Bridges JSON structures into NetLogo's type system.
 *
 * NetLogo has no map/dictionary type — only strings, numbers, booleans, lists,
 * and agents. A JSON object therefore becomes a list of `[key value]` pairs,
 * which is the shape NetLogo modelers already use for association lists and
 * which [[lookup]] can search.
 */
object JsonToNetLogo {

  /**
   * Parse a JSON string and convert it to a NetLogo value.
   *
   * @throws IllegalArgumentException if the text is not valid JSON
   */
  def parse(jsonText: String): AnyRef = {
    val parsed =
      try ujson.read(jsonText)
      catch {
        case e: Exception =>
          throw new IllegalArgumentException(
            s"Response was not valid JSON: ${e.getMessage}. Response text: $jsonText"
          )
      }
    convert(parsed)
  }

  /**
   * Parse a JSON string that must be an object, and convert it.
   *
   * Callers that report a NetLogo list need the `[key value]` pair shape, which
   * only a JSON object produces. A model that ignores its schema and replies
   * with a bare scalar would otherwise yield a value of the wrong NetLogo type.
   *
   * @throws IllegalArgumentException if the text is not valid JSON or not an object
   */
  def parseObject(jsonText: String): LogoList = {
    val parsed =
      try ujson.read(jsonText)
      catch {
        case e: Exception =>
          throw new IllegalArgumentException(
            s"Response was not valid JSON: ${e.getMessage}. Response text: $jsonText"
          )
      }

    parsed match {
      case obj: ujson.Obj => convert(obj).asInstanceOf[LogoList]
      case other =>
        throw new IllegalArgumentException(
          s"expected a JSON object in the reply, but got ${typeName(other)}. Response text: $jsonText"
        )
    }
  }

  private def typeName(value: ujson.Value): String = value match {
    case _: ujson.Obj  => "an object"
    case _: ujson.Arr  => "an array"
    case _: ujson.Str  => "a string"
    case _: ujson.Num  => "a number"
    case _: ujson.Bool => "a boolean"
    case ujson.Null    => "null"
  }

  /**
   * Recursively convert a JSON value to its NetLogo equivalent.
   *
   * Numbers box to Double because NetLogo has a single numeric type; null maps
   * to the empty string because NetLogo has no null and an empty string is a
   * value modelers can compare against without a runtime error.
   */
  def convert(value: ujson.Value): AnyRef = value match {
    case obj: ujson.Obj =>
      LogoList.fromIterator(
        obj.value.iterator.map { case (key, v) => LogoList(key, convert(v)) }
      )
    case arr: ujson.Arr =>
      LogoList.fromIterator(arr.value.iterator.map(convert))
    case ujson.Str(s)  => s
    case ujson.Num(n)  => Double.box(n)
    case ujson.Bool(b) => Boolean.box(b)
    case ujson.Null    => ""
  }

  /**
   * Find the value for `key` in a list of `[key value]` pairs.
   *
   * Matching is exact and case-sensitive, because JSON keys are. Entries that
   * are not two-element lists are skipped rather than matched positionally, so
   * asking a plain list such as `["a" "b"]` for key "a" misses instead of
   * returning a neighbouring element.
   *
   * @return the value, or None if no pair has that key
   */
  def lookup(list: LogoList, key: String): Option[AnyRef] =
    list.toVector.collectFirst {
      case pair: LogoList if pair.size == 2 && pair(0) == key => pair(1)
    }
}
