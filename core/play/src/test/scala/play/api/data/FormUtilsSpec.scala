/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */

package play.api.data

import org.specs2.mutable.Specification
import play.api.libs.json.{JsNull, Json}

class FormUtilsSpec extends Specification {

  "FormUtils.fromJson" should {
    "convert a complex json structure to a map" in {
      val json = Json.obj(
        "arr" -> Json.arr(
          Json.obj(
            "a" -> "an-a",
            "b" -> true,
            "c" -> JsNull,
            "d" -> 10
          ),
          "str",
          20,
          "blah"
        ),
        "e" -> Json.obj(
          "f" -> "an-f",
          "g" -> false,
        ),
        "h" -> "an-h",
        "i" -> 30
      )

      val expected = Seq(
        "arr[0].a" -> "an-a",
        "arr[0].b" -> "true",
        "arr[0].d" -> "10",
        "arr[1]" -> "str",
        "arr[2]" -> "20",
        "arr[3]" -> "blah",
        "e.f" -> "an-f",
        "e.g" -> "false",
        "h" -> "an-h",
        "i" -> "30"
      )

      val map = FormUtils.fromJson(json, 1000)
      map.toSeq must containTheSameElementsAs(expected)
    }

    "not stack overflow when converting heavily nested arrays" in {
      try {
        FormUtils.fromJson(Json.parse("{\"arr\":" + ("[" * 10000) + "1" + ("]" * 10000) + "}"), 1000000)
      } catch {
        case e: StackOverflowError =>
          ko("StackOverflowError thrown")
      }
      ok
    }

    "not run out of heap when converting arrays with very long keys" in {
      (try {
        FormUtils.fromJson(
          // A JSON object with a key of length 10000, pointing to a list with 100000 elements.
          // In memory, this will consume at most a few MB of space. When expanded, will consume
          // many GB of space.
          Json.obj("a" * 10000 -> Json.arr(1000000 to 1100000)), 1000000
        )
      } catch {
        case _: OutOfMemoryError =>
          // No guarantee we'll be the thread that gets this, or that our handling will be graceful,
          // but at least try.
          ko("OutOfMemoryError")
      }) must throwA[FormJsonExpansionTooLarge]
    }
  }

}
