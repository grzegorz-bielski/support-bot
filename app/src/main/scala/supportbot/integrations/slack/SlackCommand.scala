package supportbot
package integrations
package slack

import cats.syntax.all.*
import org.http4s.FormDataDecoder
import org.http4s.FormDataDecoder.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

sealed trait SlackCommand

final case class SlashCommandPayload(
  apiAppId: String,
  teamId: String,
  teamDomain: String,
  enterpriseId: Option[String],
  enterpriseName: Option[String],
  channelId: String,
  channelName: String,
  userId: String,
  command: String,
  text: String,
  responseUrl: String,
  triggerId: String,
  isEnterpriseInstall: Boolean,
) extends SlackCommand derives ConfiguredJsonValueCodec

object SlashCommandPayload:
  given FormDataDecoder[SlashCommandPayload] = (
    field[String]("api_app_id").sanitized,
    field[String]("team_id").sanitized,
    field[String]("team_domain").sanitized,
    fieldOptional[String]("enterprise_id").sanitized,
    fieldOptional[String]("enterprise_name").sanitized,
    field[String]("channel_id").sanitized,
    field[String]("channel_name").sanitized,
    field[String]("user_id").sanitized,
    field[String]("command").sanitized,
    field[String]("text").sanitized,
    field[String]("response_url").sanitized,
    field[String]("trigger_id").sanitized,
    field[Boolean]("is_enterprise_install").sanitized,
  ).mapN(SlashCommandPayload.apply)
