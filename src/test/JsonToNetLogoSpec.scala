// ABOUTME: Deterministic tests for recursive JSON to NetLogo value conversion and key lookup
// ABOUTME: Covers every JSON type, nesting, and the lookup semantics behind llm:get
package org.nlogo.extensions.llm.utils

import org.nlogo.core.LogoList
import org.scalatest.funsuite.AnyFunSuite

class JsonToNetLogoSpec extends AnyFunSuite {

  private def convert(text: String): AnyRef = JsonToNetLogo.convert(ujson.read(text))

  // --- Scalars ---

  test("a string converts to a NetLogo string") {
    assert(convert(""""hello"""") == "hello")
  }

  test("a number converts to a boxed Double") {
    assert(convert("42") == Double.box(42.0))
    assert(convert("3.14") == Double.box(3.14))
  }

  test("a boolean converts to a boxed Boolean") {
    assert(convert("true") == Boolean.box(true))
    assert(convert("false") == Boolean.box(false))
  }

  test("null converts to an empty string") {
    // NetLogo has no null. Empty string is the value modelers can test with
    // `= \"\"` without a runtime error, which nobody is available for otherwise.
    assert(convert("null") == "")
  }

  // --- Containers ---

  test("an array converts to a flat NetLogo list") {
    val result = convert("""["a","b","c"]""").asInstanceOf[LogoList]
    assert(result.toVector == Vector("a", "b", "c"))
  }

  test("an object converts to a list of [key value] pairs preserving order") {
    val result = convert("""{"name":"Alice","age":30}""").asInstanceOf[LogoList]
    assert(result.size == 2)
    val first = result(0).asInstanceOf[LogoList]
    assert(first.toVector == Vector("name", "Alice"))
    val second = result(1).asInstanceOf[LogoList]
    assert(second.toVector == Vector("age", Double.box(30.0)))
  }

  test("an empty object converts to an empty list") {
    assert(convert("{}").asInstanceOf[LogoList].size == 0)
  }

  test("nested objects convert recursively") {
    val result = convert("""{"addr":{"city":"Chicago"}}""").asInstanceOf[LogoList]
    val addrPair = result(0).asInstanceOf[LogoList]
    assert(addrPair(0) == "addr")
    val inner = addrPair(1).asInstanceOf[LogoList]
    assert(inner(0).asInstanceOf[LogoList].toVector == Vector("city", "Chicago"))
  }

  test("arrays of objects convert recursively") {
    val result = convert("""{"steps":[{"n":1},{"n":2}]}""").asInstanceOf[LogoList]
    val steps = result(0).asInstanceOf[LogoList](1).asInstanceOf[LogoList]
    assert(steps.size == 2)
    val firstStep = steps(0).asInstanceOf[LogoList](0).asInstanceOf[LogoList]
    assert(firstStep.toVector == Vector("n", Double.box(1.0)))
  }

  // --- parse entry point ---

  test("parse accepts a JSON string and converts it") {
    val result = JsonToNetLogo.parse("""{"a":1}""").asInstanceOf[LogoList]
    assert(result(0).asInstanceOf[LogoList].toVector == Vector("a", Double.box(1.0)))
  }

  test("parse rejects malformed JSON with a message quoting the text") {
    val ex = intercept[IllegalArgumentException] { JsonToNetLogo.parse("""{"a":""") }
    assert(ex.getMessage.toLowerCase.contains("json"))
  }

  // --- parseObject: the object-only entry point ---

  test("parseObject accepts an object and reports pairs") {
    val result = JsonToNetLogo.parseObject("""{"a":1}""")
    assert(result(0).asInstanceOf[LogoList].toVector == Vector("a", Double.box(1.0)))
  }

  test("parseObject rejects a top-level scalar naming the type it got") {
    val ex = intercept[IllegalArgumentException] { JsonToNetLogo.parseObject(""""hi"""") }
    assert(ex.getMessage.contains("a string"))
    assert(ex.getMessage.contains("expected a JSON object"))
  }

  test("parseObject rejects a top-level array") {
    // A flat list would not be readable by llm:get, so this must not slip through.
    val ex = intercept[IllegalArgumentException] { JsonToNetLogo.parseObject("""["a"]""") }
    assert(ex.getMessage.contains("an array"))
  }

  test("parseObject rejects malformed JSON") {
    val ex = intercept[IllegalArgumentException] { JsonToNetLogo.parseObject("""{"a":""") }
    assert(ex.getMessage.contains("not valid JSON"))
  }

  // --- Key lookup (llm:get) ---

  test("lookup finds a top-level key") {
    val parsed = convert("""{"name":"Alice","age":30}""").asInstanceOf[LogoList]
    assert(JsonToNetLogo.lookup(parsed, "name").contains("Alice"))
    assert(JsonToNetLogo.lookup(parsed, "age").contains(Double.box(30.0)))
  }

  test("lookup returns None for a missing key rather than guessing") {
    val parsed = convert("""{"name":"Alice"}""").asInstanceOf[LogoList]
    assert(JsonToNetLogo.lookup(parsed, "nope").isEmpty)
  }

  test("lookup returns a nested structure so chained gets work") {
    val parsed = convert("""{"addr":{"city":"Chicago"}}""").asInstanceOf[LogoList]
    val addr = JsonToNetLogo.lookup(parsed, "addr").get.asInstanceOf[LogoList]
    assert(JsonToNetLogo.lookup(addr, "city").contains("Chicago"))
  }

  test("lookup is exact-match and case-sensitive, matching JSON key semantics") {
    val parsed = convert("""{"Name":"Alice"}""").asInstanceOf[LogoList]
    assert(JsonToNetLogo.lookup(parsed, "name").isEmpty)
    assert(JsonToNetLogo.lookup(parsed, "Name").contains("Alice"))
  }

  test("lookup ignores entries that are not [key value] pairs") {
    // A plain list such as ["a" "b" "c"] is not a key-value structure; asking
    // for a key must miss rather than match a bare element.
    val plain = convert("""["a","b"]""").asInstanceOf[LogoList]
    assert(JsonToNetLogo.lookup(plain, "a").isEmpty)
  }

  test("lookup returns the first match when a key repeats") {
    val list = LogoList(LogoList("k", "first"), LogoList("k", "second"))
    assert(JsonToNetLogo.lookup(list, "k").contains("first"))
  }
}
