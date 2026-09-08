// ABOUTME: Deterministic tests for ResponseFormat parsing, validation, and schema normalization
// ABOUTME: Asserts modeler-supplied schema strings are rejected with clear messages or normalized consistently
package org.nlogo.extensions.llm.models

import org.scalatest.funsuite.AnyFunSuite

class ResponseFormatSpec extends AnyFunSuite {

  private def keys(v: ujson.Value): Set[String] = v.obj.keys.toSet

  // --- Parsing modeler-supplied schema strings ---

  test("a valid object schema parses into JsonSchemaFormat") {
    val fmt = ResponseFormat.parseSchema(
      """{"type":"object","properties":{"action":{"type":"string"}},"required":["action"]}"""
    )
    assert(fmt.schema("type").str == "object")
  }

  test("malformed JSON is rejected naming the schema, not a raw parser dump") {
    val ex = intercept[IllegalArgumentException] {
      ResponseFormat.parseSchema("""{"type":"object",""")
    }
    assert(ex.getMessage.toLowerCase.contains("schema"))
    assert(ex.getMessage.toLowerCase.contains("valid json"))
  }

  test("a JSON value that is not an object is rejected") {
    val ex = intercept[IllegalArgumentException] {
      ResponseFormat.parseSchema("""["a","b"]""")
    }
    assert(ex.getMessage.contains("JSON object"))
  }

  test("an empty schema string is rejected") {
    val ex = intercept[IllegalArgumentException] {
      ResponseFormat.parseSchema("   ")
    }
    assert(ex.getMessage.toLowerCase.contains("empty"))
  }

  test("a schema missing a type is rejected with a message naming 'type'") {
    val ex = intercept[IllegalArgumentException] {
      ResponseFormat.parseSchema("""{"properties":{"a":{"type":"string"}}}""")
    }
    assert(ex.getMessage.contains("type"))
  }

  test("an object schema with no properties is rejected") {
    val ex = intercept[IllegalArgumentException] {
      ResponseFormat.parseSchema("""{"type":"object"}""")
    }
    assert(ex.getMessage.contains("properties"))
  }

  test("an enum that is not an array is rejected locally rather than at the provider") {
    // Every provider requires enum to be a list of values. Catching it here
    // turns a provider-specific 400 into a message naming the offending field.
    val ex = intercept[IllegalArgumentException] {
      ResponseFormat.parseSchema("""{"type":"object","properties":{"d":{"type":"string","enum":"north"}}}""")
    }
    assert(ex.getMessage.contains("enum"))
    assert(ex.getMessage.contains("d"))
  }

  test("an empty enum array is rejected") {
    val ex = intercept[IllegalArgumentException] {
      ResponseFormat.parseSchema("""{"type":"object","properties":{"d":{"type":"string","enum":[]}}}""")
    }
    assert(ex.getMessage.contains("enum"))
  }

  test("a valid enum is accepted and preserved through normalization") {
    val fmt = ResponseFormat.parseSchema(
      """{"type":"object","properties":{"d":{"type":"string","enum":["north","south"]}}}"""
    )
    val strict = ResponseFormat.strictSchema(fmt.schema)
    assert(strict("properties")("d")("enum").arr.map(_.str) == Seq("north", "south"))
  }

  // --- Strict normalization (OpenAI/Anthropic requirements) ---

  test("normalization injects additionalProperties false on an object schema") {
    val schema = ResponseFormat.parseSchema(
      """{"type":"object","properties":{"a":{"type":"string"}},"required":["a"]}"""
    ).schema
    val strict = ResponseFormat.strictSchema(schema)
    assert(strict("additionalProperties").bool == false)
  }

  test("normalization lists every property in required, not just the declared ones") {
    val schema = ResponseFormat.parseSchema(
      """{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"number"}},"required":["a"]}"""
    ).schema
    val strict = ResponseFormat.strictSchema(schema)
    assert(strict("required").arr.map(_.str).toSet == Set("a", "b"))
  }

  test("normalization recurses into nested object properties") {
    val schema = ResponseFormat.parseSchema(
      """{"type":"object","properties":{"addr":{"type":"object","properties":{"city":{"type":"string"}}}}}"""
    ).schema
    val strict = ResponseFormat.strictSchema(schema)
    val nested = strict("properties")("addr")
    assert(nested("additionalProperties").bool == false)
    assert(nested("required").arr.map(_.str) == Seq("city"))
  }

  test("normalization recurses into array item schemas") {
    val schema = ResponseFormat.parseSchema(
      """{"type":"object","properties":{"steps":{"type":"array","items":{"type":"object","properties":{"n":{"type":"number"}}}}}}"""
    ).schema
    val strict = ResponseFormat.strictSchema(schema)
    val items = strict("properties")("steps")("items")
    assert(items("additionalProperties").bool == false)
    assert(items("required").arr.map(_.str) == Seq("n"))
  }

  test("normalization does not mutate the caller's schema") {
    val fmt = ResponseFormat.parseSchema(
      """{"type":"object","properties":{"a":{"type":"string"}}}"""
    )
    ResponseFormat.strictSchema(fmt.schema)
    assert(!keys(fmt.schema).contains("additionalProperties"), "strictSchema must return a copy")
  }

  // --- Enum format ---

  test("EnumFormat renders as a single-property object schema constraining the choice") {
    val schema = EnumFormat(List("north", "south")).schema
    assert(schema("type").str == "object")
    val choiceProp = schema("properties")(EnumFormat.ChoiceKey)
    assert(choiceProp("enum").arr.map(_.str) == Seq("north", "south"))
  }

  test("EnumFormat rejects an empty choice list") {
    intercept[IllegalArgumentException] { EnumFormat(Nil) }
  }

  // --- Serialization ---

  test("a ChatRequest carrying a response format round-trips through upickle") {
    // ChatRequest exposes a derived ReadWriter. Adding a sealed-trait field
    // breaks that derivation unless ResponseFormat provides its own instance.
    val request = ChatRequest(
      model = "m",
      messages = Seq(ChatMessage.user("hi")),
      responseFormat = Some(EnumFormat(List("a", "b")))
    )
    val restored = upickle.default.read[ChatRequest](upickle.default.write(request))
    assert(restored.responseFormat.contains(EnumFormat(List("a", "b"))))
  }

  test("a JsonSchemaFormat round-trips with its schema intact") {
    val fmt = ResponseFormat.parseSchema("""{"type":"object","properties":{"a":{"type":"string"}}}""")
    val restored = upickle.default.read[ResponseFormat](upickle.default.write[ResponseFormat](fmt))
    assert(restored.asInstanceOf[JsonSchemaFormat].schema("properties")("a")("type").str == "string")
  }

  test("JsonObjectFormat round-trips") {
    val restored = upickle.default.read[ResponseFormat](upickle.default.write[ResponseFormat](JsonObjectFormat))
    assert(restored == JsonObjectFormat)
  }
}
