package play.api.http

import play.api.mvc._
import play.api.libs.json._

import scala.annotation._
import scala.language.reflectiveCalls
import play.api.libs.iteratee.{Enumerator, Enumeratee}

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.MultipartFormData.FilePart
import java.io.{InputStream, File}

/**
 * Transform a value of type A to a Byte Array.
 *
 * @tparam A the content type
 */
@implicitNotFound(
  "Cannot write an instance of ${A} to HTTP response. Try to define a Writeable[${A}]"
)
case class Writeable[-A](transform: (A => Array[Byte]), contentType: Option[String]) {
  def map[B](f: B => A): Writeable[B] = Writeable(b => transform(f(b)), contentType)

  /**
   * Convert this writable into an enumeratee
   */
  def toEnumeratee[E <: A]: Enumeratee[E, Array[Byte]] = Enumeratee.mapFlatten(mp => enumeratorFor(mp)._1)

  /**
   * Create an enumerator for the given content.
   *
   * Sub classes may override this if they want to calculate a content length.
   *
   * @return An enumerator, and the content length, if known.
   */
  def enumeratorFor(a: A): (Enumerator[Array[Byte]], Option[Long]) = {
    val bytes = transform(a)
    // Send none as the length, because we're going to buffer the first chunk anyway, and so will know what the
    // length is then.
    (Enumerator(bytes), None)
  }
}

/**
 * Helper utilities for `Writeable`.
 */
object Writeable extends DefaultWriteables {

  /**
   * Creates a `Writeable[A]` using a content type for `A` available in the implicit scope
   * @param transform Serializing function
   */
  def apply[A](transform: A => Array[Byte])(implicit ct: ContentTypeOf[A]): Writeable[A] =
    Writeable(transform, ct.mimeType)

}

/**
 * Default Writeable with lowwe priority.
 */
trait LowPriorityWriteables {

  /**
   * `Writeable` for `Content` values.
   */
  implicit def writeableOf_Content[C <: Content](implicit codec: Codec, ct: ContentTypeOf[C]): Writeable[C] = {
    Writeable(content => codec.encode(content.body))
  }

}

/**
 * Default Writeable.
 */
trait DefaultWriteables extends LowPriorityWriteables {

  /**
   * `Writeable` for `NodeSeq` values - literal Scala XML.
   */
  implicit def writeableOf_NodeSeq[C <: scala.xml.NodeSeq](implicit codec: Codec): Writeable[C] = {
    Writeable(xml => codec.encode(xml.toString))
  }

  /**
   * `Writeable` for `NodeBuffer` values - literal Scala XML.
   */
  implicit def writeableOf_NodeBuffer(implicit codec: Codec): Writeable[scala.xml.NodeBuffer] = {
    Writeable(xml => codec.encode(xml.toString))
  }

  /**
   * `Writeable` for `urlEncodedForm` values
   */
  implicit def writeableOf_urlEncodedForm(implicit codec: Codec): Writeable[Map[String, Seq[String]]] = {
    import java.net.URLEncoder
    Writeable(formData =>
      codec.encode(formData.map(item => item._2.map(c => item._1 + "=" + URLEncoder.encode(c, "UTF-8"))).flatten.mkString("&"))
    )
  }

  /**
   * `Writeable` for multipart/form-data.
   *
   * Note: You probably never want to use the transform method of this enumerator, since it will load all content into
   * memory.
   */
  implicit def writeableOf_multiPartFormData[A](implicit codec: Codec, aWriteable: Writeable[A]): Writeable[MultipartFormData[A]] = {

    val boundary = scala.util.Random.alphanumeric.take(20).mkString("")

    def formatDataParts(data: Map[String, Seq[String]]) = codec.encode(data.map { entry =>
      entry._2.map { value =>
        val name ="\"" + entry._1 + "\""
        s"--$boundary\r\n${HeaderNames.CONTENT_DISPOSITION}: form-data; name=$name\r\n\r\n$value\r\n"
      }
    }.mkString(""))

    def filePartHeader(file: FilePart[A]) = {
      val name = "\"" + file.key + "\""
      val filename = "\"" + file.filename + "\""
      val header1 = s"--$boundary\r\n${HeaderNames.CONTENT_DISPOSITION}: form-data; name=$name; filename=$filename\r\n"
      codec.encode(header1 + file.contentType.map(ct => HeaderNames.CONTENT_TYPE + ": " + ct + "\r\n"))
    }

    new Writeable[MultipartFormData[A]](a => a.files.foldLeft(formatDataParts(a.dataParts)) { (bytes, file) =>
      bytes ++ filePartHeader(file) ++ aWriteable.transform(file.ref) ++ codec.encode("\r\n")
    }, Option("multipart/form-data, boundary=" + boundary)) {

      override def enumeratorFor(multipart: MultipartFormData[A]) = {

        val data = formatDataParts(multipart.dataParts)

        val fileParts = multipart.files.map { file =>
          val header = filePartHeader(file)
          val trailer = codec.encode("\r\n")
          val (body, bodyLength) = aWriteable.enumeratorFor(file.ref)
          (Enumerator(header) >>> body >>> Enumerator(trailer), bodyLength.map(_ + header.length + trailer.length))
        }

        val dataLength: Option[Long] = Some(data.length)

        fileParts.foldLeft((Enumerator(data), dataLength)) { (combined, next) =>
          (combined._1.andThen(next._1), combined._2.flatMap(l => next._2.map(_ + l)))
        }
      }
    }
  }

  /**
   * `Writeable` for generic files.
   *
   * This writable makes no attempt to guess the content type of the file.
   *
   * Note: You probably never want to use the transform method of this enumerator, since it will load all content into
   * memory.
   */
  implicit def writeableOf_File: Writeable[File] = {
    new Writeable[File](file => scalax.io.Resource.fromFile(file).byteArray, None) {
      override def enumeratorFor(file: File) = (Enumerator.fromFile(file), Some(file.length()))
    }
  }

  /**
   * `Writeable` for generic streams.
   *
   * Note: You probably never want to use the transform method of this enumerator, since it will load all content into
   * memory.
   */
  implicit def writeableOf_OutputStream: Writeable[InputStream] = {
    new Writeable[InputStream](stream => scalax.io.Resource.fromInputStream(stream).byteArray, None) {
      override def enumeratorFor(stream: InputStream) = (Enumerator.fromStream(stream), None)
    }
  }

  /**
   * `Writeable` for `JsValue` values - Json
   */
  implicit def writeableOf_JsValue(implicit codec: Codec): Writeable[JsValue] = {
    Writeable(jsval => codec.encode(jsval.toString))
  }

  /**
   * `Writeable` for empty responses.
   */
  implicit val writeableOf_EmptyContent: Writeable[Results.EmptyContent] = Writeable(_ => Array.empty)

  /**
   * Straightforward `Writeable` for String values.
   */
  implicit def wString(implicit codec: Codec): Writeable[String] = Writeable[String](str => codec.encode(str))

  /**
   * Straightforward `Writeable` for Array[Byte] values.
   */
  implicit val wBytes: Writeable[Array[Byte]] = Writeable(identity)

}

