package supportbot
package integrations
package slack

import cats.effect.*
import cats.syntax.all.*
import cats.effect.syntax.all.*

import io.laserdisc.slack4s.slack.*
import io.laserdisc.slack4s.slashcmd.*

import supportbot.chat.*

// see: https://github.com/laserdisc-io/slack4s/blob/main/docs/tutorial.md
// use cloudflare tunnel instead of ngrok to expose localhost to the internet?

object SlackCommandMapperService:
    def of(using chatService: ChatService[IO]): Resource[IO, CommandMapper[IO]] = 
        Resource.pure: payload => 
            val userQuery = payload.getText.trim // TODO: sanitize it
  
            Command(
                handler = 
                    // chatService.ask(
                    //     ???
                    //     //   ChatService.Input(
                    //     //      contextId = context.id, // from the user query, somehow... do we need an alias?
                    //     //      query = query, // from the user query
                    //     //      queryId = queryId, // dynamic
                    //     //      promptTemplate = context.promptTemplate, // from the retrieved context
                    //     //      retrieveOptions = RetrieveOptions( // should be a part of the context...
                    //     //        topK = 15,
                    //     //        fragmentLookupRange = LookupRange(5, 5),
                    //     //      ),
                    //     //      chatModel = context.chatModel,
                    //     //      embeddingsModel = context.embeddingsModel,
                    //     //    ),
                    // ),
                    IO.pure(slackMessage(textSection(s"echo: ${payload.getText.trim}"))),
                responseType = 
                    DelayedWithMsg(
                        slackMessage(headerSection("Thinking..."))
                    )
            ).pure[IO]  
