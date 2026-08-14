// ABOUTME: Response-format model describing how an LLM's output should be constrained
// ABOUTME: Parses and normalizes modeler-supplied JSON Schema strings into a provider-neutral form
package org.nlogo.extensions.llm.models

/**
 * How a provider should constrain the shape of its reply.
 *
 * Provider-neutral by design: each provider decides how to wrap these in its own
 * request body (see the `applyResponseFormat` hooks in the providers package).
 */
sealed trait ResponseFormat

/**
 * Constrain output to a JSON Schema.
 *
 * `schema` is always a JSON object, validated at construction by
 * [[ResponseFormat.parseSchema]]. Providers that require strict-mode schemas
 * pass it through [[ResponseFormat.strictSchema]] first.
 */
case class JsonSchemaFormat(schema: ujson.Obj, name: String = ResponseFormat.DefaultSchemaName)
  extends ResponseFormat

/**
 * Constrain output to exactly one of a fixed list of strings.
 *
 * Rendered as a one-property object schema rather than a bare `{"enum": [...]}`,
 * because the providers that support constrained decoding all accept an object
 * schema, while a top-level enum is accepted only by some. One shape works
 * everywhere, so `llm:choose` behaves the same regardless of provider.
 */
case class EnumFormat(choices: List[String]) extends ResponseFormat {
  require(choices.nonEmpty, "Choice list cannot be empty")

  /** The enum rendered as an object schema with a single constrained property. */
  def schema: ujson.Obj =
    ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        EnumFormat.ChoiceKey -> ujson.Obj(
          "type" -> "string",
          "enum" -> ujson.Arr(choices.map(c => ujson.Str(c))*)
        )
      ),
      "required" -> ujson.Arr(ujson.Str(EnumFormat.ChoiceKey)),
      "additionalProperties" -> false
    )
}

object EnumFormat {
  /** Property name holding the selected option in an enum-constrained reply. */
  val ChoiceKey: String = "choice"
}

/** Constrain output to syntactically valid JSON, with no schema. */
case object JsonObjectFormat extends ResponseFormat

object ResponseFormat {

  /** Schema name sent to providers that require one (OpenAI). */
  val DefaultSchemaName: String = "netlogo_schema"

  /**
   * Serialization for the sealed hierarchy.
   *
   * Hand-written rather than derived: ujson.Obj has no upickle ReadWriter, and
   * ChatRequest's derived instance needs one for this field to exist at all.
   */
  implicit val rw: upickle.default.ReadWriter[ResponseFormat] =
    upickle.default.readwriter[ujson.Value].bimap[ResponseFormat](
      {
        case JsonSchemaFormat(schema, name) =>
          ujson.Obj("kind" -> "json_schema", "name" -> name, "schema" -> schema)
        case EnumFormat(choices) =>
          ujson.Obj("kind" -> "enum", "choices" -> ujson.Arr(choices.map(ujson.Str(_))*))
        case JsonObjectFormat =>
          ujson.Obj("kind" -> "json_object")
      },
      json =>
        json("kind").str match {
          case "json_schema" =>
            JsonSchemaFormat(ujson.Obj.from(json("schema").obj), json("name").str)
          case "enum" =>
            EnumFormat(json("choices").arr.map(_.str).toList)
          case "json_object" =>
            JsonObjectFormat
          case other =>
            throw new IllegalArgumentException(s"Unknown response format kind: $other")
        }
    )

  /**
   * Parse and validate a modeler-supplied JSON Schema string.
   *
   * Validation is deliberately front-loaded: an unusable schema otherwise fails
   * as an opaque provider 400 after a network round-trip, which a modeler cannot
   * act on. Errors here name what is wrong with the schema they wrote.
   *
   * @throws IllegalArgumentException if the string is not a usable object schema
   */
  def parseSchema(schemaText: String): JsonSchemaFormat = {
    if (schemaText == null || schemaText.trim.isEmpty) {
      throw new IllegalArgumentException(
        "Schema cannot be empty. Provide a JSON Schema object, e.g. " +
          """{"type":"object","properties":{"action":{"type":"string"}}}"""
      )
    }

    val parsed =
      try ujson.read(schemaText)
      catch {
        case e: Exception =>
          throw new IllegalArgumentException(
            s"Schema is not valid JSON: ${e.getMessage}"
          )
      }

    val obj = parsed match {
      case o: ujson.Obj => o
      case other =>
        throw new IllegalArgumentException(
          s"Schema must be a JSON object, but got ${typeName(other)}. " +
            """Example: {"type":"object","properties":{"action":{"type":"string"}}}"""
        )
    }

    validate(obj, path = "schema")
    JsonSchemaFormat(obj)
  }

  /**
   * Check a schema is one a provider can actually enforce.
   *
   * Only the constraints every supported provider shares are checked. Anything
   * provider-specific stays out, so a schema accepted here is not silently
   * rejected by a different provider later.
   */
  private def validate(schema: ujson.Obj, path: String): Unit = {
    val typeValue = schema.value.get("type").map {
      case ujson.Str(s) => s
      case other =>
        throw new IllegalArgumentException(
          s"$path: 'type' must be a string, but got ${typeName(other)}"
        )
    }.getOrElse {
      throw new IllegalArgumentException(
        s"$path is missing a 'type' field. Every schema needs one, e.g. " +
          """{"type":"object","properties":{...}}"""
      )
    }

    // `enum` is checked because every provider requires a non-empty array here
    // and `llm:choose` builds one itself. Other keywords are passed through
    // unchecked on purpose: the four providers accept different JSON Schema
    // dialects, so validating them all here would reject schemas a provider
    // would have honoured.
    schema.value.get("enum").foreach {
      case arr: ujson.Arr =>
        if (arr.value.isEmpty) {
          throw new IllegalArgumentException(s"$path: 'enum' must list at least one value")
        }
      case other =>
        throw new IllegalArgumentException(
          s"$path: 'enum' must be an array of values, but got ${typeName(other)}. " +
            """Example: {"type":"string","enum":["north","south"]}"""
        )
    }

    typeValue match {
      case "object" =>
        val props = schema.value.get("properties") match {
          case Some(o: ujson.Obj) => o
          case Some(other) =>
            throw new IllegalArgumentException(
              s"$path: 'properties' must be a JSON object, but got ${typeName(other)}"
            )
          case None =>
            throw new IllegalArgumentException(
              s"$path of type 'object' is missing 'properties'. An object schema with no " +
                "properties constrains nothing, so no provider can enforce it."
            )
        }
        if (props.value.isEmpty) {
          throw new IllegalArgumentException(
            s"$path: 'properties' is empty. An object schema must declare at least one property."
          )
        }
        props.value.foreach { case (key, value) =>
          value match {
            case o: ujson.Obj => validate(o, s"$path.properties.$key")
            case other =>
              throw new IllegalArgumentException(
                s"$path.properties.$key must be a schema object, but got ${typeName(other)}"
              )
          }
        }

      case "array" =>
        schema.value.get("items") match {
          case Some(o: ujson.Obj) => validate(o, s"$path.items")
          case Some(other) =>
            throw new IllegalArgumentException(
              s"$path.items must be a schema object, but got ${typeName(other)}"
            )
          case None =>
            throw new IllegalArgumentException(
              s"$path of type 'array' is missing 'items'. Declare the element schema, e.g. " +
                """{"type":"array","items":{"type":"string"}}"""
            )
        }

      case "string" | "number" | "integer" | "boolean" | "null" => ()

      case other =>
        throw new IllegalArgumentException(
          s"$path: unsupported 'type' value '$other'. Supported: " +
            "object, array, string, number, integer, boolean, null"
        )
    }
  }

  /**
   * Return a strict-mode copy of a schema.
   *
   * OpenAI and Anthropic both reject schemas that omit `additionalProperties:
   * false` or leave properties out of `required`. Applying the same
   * normalization for every provider means one schema behaves identically
   * everywhere, rather than a modeler's schema working on Ollama and 400ing on
   * OpenAI.
   *
   * The input is never mutated — ujson values are mutable, and the caller's
   * schema is reused across requests.
   */
  def strictSchema(schema: ujson.Value): ujson.Value = schema match {
    case obj: ujson.Obj =>
      val out = ujson.Obj()
      obj.value.foreach {
        case ("properties", props: ujson.Obj) =>
          val strictProps = ujson.Obj()
          props.value.foreach { case (k, v) => strictProps(k) = strictSchema(v) }
          out("properties") = strictProps
        case ("items", items) =>
          out("items") = strictSchema(items)
        case (k, v) =>
          out(k) = v
      }

      if (obj.value.get("type").exists(_ == ujson.Str("object"))) {
        out("additionalProperties") = false
        obj.value.get("properties") match {
          case Some(props: ujson.Obj) =>
            out("required") = ujson.Arr(props.value.keys.map(k => ujson.Str(k)).toSeq*)
          case _ => ()
        }
      }
      out

    case other => other
  }

  private def typeName(value: ujson.Value): String = value match {
    case _: ujson.Obj  => "an object"
    case _: ujson.Arr  => "an array"
    case _: ujson.Str  => "a string"
    case _: ujson.Num  => "a number"
    case _: ujson.Bool => "a boolean"
    case ujson.Null    => "null"
  }
}
