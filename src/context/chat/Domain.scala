package supportbot
package context
package chat

import org.http4s.FormDataDecoder
import org.http4s.FormDataDecoder.*
import java.util.UUID
import cats.effect.*
import cats.syntax.all.*

opaque type QueryId = UUID
object QueryId:
  inline def apply(uuid: UUID): QueryId = uuid
  def of: IO[QueryId]                   = IO.randomUUID

final case class ChatQuery(content: String)
object ChatQuery:
  // TODO: move this codec to controller ?
  given FormDataDecoder[ChatQuery] = (
    field[String]("content")
  ).map(ChatQuery.apply)


enum ChatEvent:
  case QueryResponse, QueryClose
