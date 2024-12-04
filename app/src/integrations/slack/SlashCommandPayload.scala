package supportbot
package integrations
package slack

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

final case class SlashCommandPayload(
  token: String,
  apiAppId: String,
  teamId: String,
  teamDomain: String,
  enterpriseId: String,
  enterpriseName: String,
  channelId: String,
  channelName: String,
  userId: String,
  userName: String,
  command: String,
  text: String,
  responseUrl: String,
  triggerId: String,
  isEnterpriseInstall: Boolean,
) derives ConfiguredJsonValueCodec
