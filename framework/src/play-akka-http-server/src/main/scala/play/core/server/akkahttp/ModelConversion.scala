package play.core.server.akkahttp

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ContentType
import akka.http.scaladsl.model.headers._
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import java.net.InetSocketAddress
import org.reactivestreams.Publisher
import play.api.Logger
import play.api.http.HeaderNames._
import play.api.libs.iteratee._
import play.api.libs.streams.Streams
import play.api.mvc._
import play.core.server.common.{ ForwardedHeaderHandler, ServerRequestUtils, ServerResultUtils }
import scala.collection.immutable
import scala.concurrent.Future

/**
 * Conversions between Akka's and Play's HTTP model objects.
 */
private[akkahttp] class ModelConversion(forwardedHeaderHandler: ForwardedHeaderHandler) {

  private val logger = Logger(getClass)

  /**
   * Convert an Akka `HttpRequest` to a `RequestHeader` and an `Eumerator`
   * for its body.
   */
  def convertRequest(
    requestId: Long,
    remoteAddress: InetSocketAddress,
    secureProtocol: Boolean,
    request: HttpRequest)(implicit fm: FlowMaterializer): (RequestHeader, Source[Array[Byte], Any]) = {
    (
      convertRequestHeader(requestId, remoteAddress, secureProtocol, request),
      convertRequestBody(request)
    )
  }

  /**
   * Convert an Akka `HttpRequest` to a `RequestHeader`.
   */
  private def convertRequestHeader(
    requestId: Long,
    remoteAddress: InetSocketAddress,
    secureProtocol: Boolean,
    request: HttpRequest): RequestHeader = {
    val remoteHostAddress = remoteAddress.getAddress.getHostAddress
    // Taken from PlayDefaultUpstreamHander

    // Avoid clash between method arg and RequestHeader field
    val remoteAddressArg = remoteAddress

    new RequestHeader {
      override val id = requestId
      // Send a tag so our tests can tell which kind of server we're using.
      // We could get NettyServer to send a similar tag, but for the moment
      // let's not, just in case it slows NettyServer down a bit.
      override val tags = Map("HTTP_SERVER" -> "akka-http")
      override def uri = request.uri.toString
      override def path = request.uri.path.toString
      override def method = request.method.name
      override def version = request.protocol.value
      override def queryString = request.uri.query.toMultiMap
      override val headers = convertRequestHeaders(request)
      override def remoteAddress: String = ServerRequestUtils.findRemoteAddress(
        forwardedHeaderHandler,
        headers,
        remoteAddressArg
      )
      override def secure: Boolean = ServerRequestUtils.findSecureProtocol(
        forwardedHeaderHandler,
        headers,
        secureProtocol
      )
    }
  }

  /**
   * Convert the request headers of an Akka `HttpRequest` to a Play
   * `Headers` object.
   */
  private def convertRequestHeaders(request: HttpRequest): Headers = {
    val entityHeaders: Seq[(String, String)] = request.entity match {
      case HttpEntity.Strict(contentType, _) =>
        Seq((CONTENT_TYPE, contentType.value))
      case HttpEntity.Default(contentType, contentLength, _) =>
        Seq((CONTENT_TYPE, contentType.value), (CONTENT_LENGTH, contentLength.toString))
      case HttpEntity.Chunked(contentType, _) =>
        Seq((CONTENT_TYPE, contentType.value))
    }
    val normalHeaders: Seq[(String, String)] = request.headers.map((rh: HttpHeader) => (rh.name, rh.value))
    new Headers(entityHeaders ++ normalHeaders)
  }

  /**
   * Convert an Akka `HttpRequest` to an `Enumerator` of the request body.
   */
  private def convertRequestBody(
    request: HttpRequest)(implicit fm: FlowMaterializer): Source[Array[Byte], Any] = {
    import play.api.libs.iteratee.Execution.Implicits.trampoline
    request.entity match {
      case HttpEntity.Strict(_, data) if data.isEmpty =>
        Source.empty
      case HttpEntity.Strict(_, data) =>
        Source.single(data.toArray)
      case HttpEntity.Default(_, 0, _) =>
        Source.empty
      case HttpEntity.Default(contentType, contentLength, pubr) =>
        // FIXME: should do something with the content-length?
        pubr.map(_.toArray)
      case HttpEntity.Chunked(contentType, chunks) =>
        // FIXME: Don't enumerate LastChunk?
        // FIXME: do something with trailing headers?
        chunks.map(_.data().toArray)
    }
  }

  /**
   * Convert a Play `Result` object into an Akka `HttpResponse` object.
   */
  def convertResult(
    requestHeaders: RequestHeader,
    result: Result,
    protocol: HttpProtocol): Future[HttpResponse] = {

    val convertedHeaders: AkkaHttpHeaders = convertResponseHeaders(result.header.headers)
    import play.api.libs.iteratee.Execution.Implicits.trampoline
    convertResultBody(requestHeaders, convertedHeaders, result, protocol).flatMap {
      case Left(alternativeResult) =>
        convertResult(requestHeaders, alternativeResult, protocol)
      case Right((entity, connection)) =>
        Future.successful(
          HttpResponse(
            status = result.header.status,
            headers = convertedHeaders.misc ++ connection,
            entity = entity,
            protocol = protocol)
        )
    }
  }

  def convertResultBody(
    requestHeaders: RequestHeader,
    convertedHeaders: AkkaHttpHeaders,
    result: Result,
    protocol: HttpProtocol): Future[Either[Result, (ResponseEntity, Option[Connection])]] = {

    import Execution.Implicits.trampoline

    def dataSource(enum: Enumerator[Array[Byte]]): Source[ByteString, Unit] = {
      val dataEnum: Enumerator[ByteString] = enum.map(ByteString(_)) >>> Enumerator.eof
      AkkaStreamsConversion.enumeratorToSource(dataEnum)
    }

    ServerResultUtils.determineResultStreaming(requestHeaders, result).map {
      case Left(ServerResultUtils.InvalidResult(reason, alternativeResult)) =>
        logger.warn(s"Cannot send result, sending error result instead: $reason")
        Left(alternativeResult)
      case Right((streaming, connectionHeader)) =>
        def valid(entity: ResponseEntity): Right[Nothing, (ResponseEntity, Option[Connection])] = {
          val akkaConnectionHeader: Option[Connection] = connectionHeader.header.map(h => Connection(h))
          Right((entity, akkaConnectionHeader))
        }
        streaming match {
          case ServerResultUtils.StreamWithClose(enum) =>
            assert(connectionHeader.willClose)
            valid(HttpEntity.CloseDelimited(
              contentType = convertedHeaders.contentType,
              data = dataSource(enum)
            ))
          case ServerResultUtils.StreamWithNoBody =>
            valid(HttpEntity.Empty)
          case ServerResultUtils.StreamWithKnownLength(enum) =>
            convertedHeaders.contentLength.get match {
              case 0 =>
                valid(HttpEntity.empty(
                  contentType = convertedHeaders.contentType
                ))
              case contentLength =>
                valid(HttpEntity.Default(
                  contentType = convertedHeaders.contentType,
                  contentLength = contentLength,
                  data = dataSource(enum)
                ))
            }
          case ServerResultUtils.StreamWithStrictBody(body) =>
            valid(HttpEntity.Strict(
              contentType = convertedHeaders.contentType,
              data = if (body.isEmpty) ByteString.empty else ByteString(body)
            ))
          case ServerResultUtils.UseExistingTransferEncoding(transferEncodedEnum) =>
            assert(convertedHeaders.transferEncoding.isDefined) // Guaranteed by ServerResultUtils
            val transferEncoding = convertedHeaders.transferEncoding.get
            transferEncoding match {
              case immutable.Seq(TransferEncodings.chunked) =>
                valid(HttpEntity.Chunked(
                  contentType = convertedHeaders.contentType,
                  chunks = dechunkAndRechunk(transferEncodedEnum)
                ))
              case other =>
                logger.warn(s"Cannot send result, sending error instead: Akka HTTP server only supports 'Transfer-Encoding: chunked', was $other")
                Left(Results.InternalServerError(""))
            }
          case ServerResultUtils.PerformChunkedTransferEncoding(enum) =>
            val chunksEnum = (
              enum.map(HttpEntity.ChunkStreamPart(_)) >>>
              Enumerator.enumInput(Input.El(HttpEntity.LastChunk)) >>>
              Enumerator.eof
            )
            val chunksSource = AkkaStreamsConversion.enumeratorToSource(chunksEnum)
            valid(HttpEntity.Chunked(
              contentType = convertedHeaders.contentType,
              chunks = chunksSource
            ))
        }
    }
  }

  private def convertHeaders(headers: Iterable[(String, String)]): immutable.Seq[HttpHeader] = {
    headers.map {
      case (name, value) =>
        HttpHeader.parse(name, value) match {
          case HttpHeader.ParsingResult.Ok(header, errors /* errors are ignored if Ok */ ) =>
            header
          case HttpHeader.ParsingResult.Error(error) =>
            sys.error(s"Error parsing header: $error")
        }
    }.to[immutable.Seq]
  }

  /**
   * Given a chunk encoded stream, decode it and reencode it in Akka's chunk format.
   */
  private def dechunkAndRechunk(chunkEncodedEnum: Enumerator[Array[Byte]]): Source[HttpEntity.ChunkStreamPart, Unit] = {
    import Execution.Implicits.trampoline
    val rechunkEnee = Results.dechunkWithTrailers ><> Enumeratee.map[Either[Array[Byte], Seq[(String, String)]]][HttpEntity.ChunkStreamPart] {
      case Left(bytes) =>
        HttpEntity.ChunkStreamPart(bytes)
      case Right(rawHeaderStrings) =>
        HttpEntity.LastChunk(trailer = convertHeaders(rawHeaderStrings))
    }
    val akkaChunksEnum: Enumerator[HttpEntity.ChunkStreamPart] = (chunkEncodedEnum &> rechunkEnee) >>> Enumerator.eof
    AkkaStreamsConversion.enumeratorToSource(akkaChunksEnum)
  }

  /**
   * A representation of Akka HTTP headers separate from an `HTTPMessage`.
   * Akka HTTP treats some headers specially and these are split out into
   * separate values.
   *
   * @misc General headers. Guaranteed not to contain any of the special
   * headers stored in the other values.
   * @contentType If present, the value of the `Content-Type` header.
   * @contentLength If present, the value of the `Content-Length` header.
   */
  case class AkkaHttpHeaders(
    misc: immutable.Seq[HttpHeader],
    contentType: ContentType,
    contentLength: Option[Long],
    transferEncoding: Option[immutable.Seq[TransferEncoding]])

  /**
   * Convert Play response headers into `HttpHeader` objects, then separate
   * out any special headers.
   */
  private def convertResponseHeaders(
    playHeaders: Map[String, String]): AkkaHttpHeaders = {
    val rawHeaders: Iterable[(String, String)] = ServerResultUtils.splitSetCookieHeaders(playHeaders)
    val convertedHeaders: Seq[HttpHeader] = convertHeaders(rawHeaders)
    val emptyHeaders = AkkaHttpHeaders(immutable.Seq.empty, ContentTypes.`application/octet-stream`, None, None)
    convertedHeaders.foldLeft(emptyHeaders) {
      case (accum, ct: `Content-Type`) =>
        accum.copy(contentType = ct.contentType)
      case (accum, cl: `Content-Length`) =>
        accum.copy(contentLength = Some(cl.length))
      case (accum, te: `Transfer-Encoding`) =>
        accum.copy(transferEncoding = Some(te.encodings))
      case (accum, miscHeader) =>
        accum.copy(misc = accum.misc :+ miscHeader)
    }
  }

}