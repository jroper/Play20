/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package play.it.http.parsing

import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Source
import play.api.mvc._
import play.api.test._

object DefaultBodyParserSpec extends PlaySpecification {

  "The default body parser" should {

    def parse(method: String, contentType: Option[String], body: Array[Byte])(implicit mat: FlowMaterializer) = {
      val request = FakeRequest(method, "/x").withHeaders(contentType.map(CONTENT_TYPE -> _).toSeq: _*)
      await(BodyParsers.parse.default(request).run(Source.single(body)))
    }

    "ignore text bodies for DELETE requests" in new WithApplication() {
      parse("GET", Some("text/plain"), "bar".getBytes("utf-8")) must beRight(AnyContentAsEmpty)
    }

    "ignore text bodies for GET requests" in new WithApplication() {
      parse("GET", Some("text/plain"), "bar".getBytes("utf-8")) must beRight(AnyContentAsEmpty)
    }

    "ignore text bodies for HEAD requests" in new WithApplication() {
      parse("HEAD", None, "bar".getBytes("utf-8")) must beRight(AnyContentAsEmpty)
    }

    "ignore text bodies for OPTIONS requests" in new WithApplication() {
      parse("GET", Some("text/plain"), "bar".getBytes("utf-8")) must beRight(AnyContentAsEmpty)
    }

    "parse XML bodies for PATCH requests" in new WithApplication() {
      parse("POST", Some("text/xml"), "<bar></bar>".getBytes("utf-8")) must beRight(AnyContentAsXml(<bar></bar>))
    }

    "parse text bodies for POST requests" in new WithApplication() {
      parse("POST", Some("text/plain"), "bar".getBytes("utf-8")) must beRight(AnyContentAsText("bar"))
    }

    "parse JSON bodies for PUT requests" in new WithApplication() {
      parse("PUT", Some("application/json"), """{"foo":"bar"}""".getBytes("utf-8")) must beRight.like {
        case AnyContentAsJson(json) => (json \ "foo").as[String] must_== "bar"
      }
    }

    "parse unknown bodies as raw for PUT requests" in new WithApplication() {
      val inBytes = Array[Byte](0)
      parse("PUT", None, inBytes) must beRight.like {
        case AnyContentAsRaw(rawBuffer) => rawBuffer.asBytes() must beSome.like {
          case outBytes => outBytes.to[Vector] must_== inBytes.to[Vector]
        }
      }
    }

  }
}
