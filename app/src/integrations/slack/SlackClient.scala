package supportbot
package integrations
package slack

import cats.effect.*
import sttp.client4.*
import sttp.client4.jsoniter.*

// https://api.slack.com/web#posting_json
// https://api.slack.com/tutorials/tracks/getting-a-token

trait SlackClient[F[_]]:
  def respondTo(responseUrl: String, response: MsgPayload): F[Unit]

final class SttpSlackClient(using backend: SttpBackend) extends SlackClient[IO]:
    def respondTo(responseUrl: String, response: MsgPayload): IO[Unit] = 
        val request = basicRequest
            .post(uri"$responseUrl")
            .body(response)
            .contentType("application/json")

        backend.send(request).void
