package supportbot

import cats.effect.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import sttp.client4.*

type SttpBackend = WebSocketStreamBackend[IO, Fs2Streams[IO]]
object SttpBackend:
  def resource: Resource[IO, SttpBackend] = HttpClientFs2Backend.resource[IO]()
