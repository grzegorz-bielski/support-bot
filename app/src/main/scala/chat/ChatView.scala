package supportbot
package chat

import scalatags.Text.all.*
import scala.concurrent.duration.{span as _, *}

object ChatView extends HtmxView:
  private val messagesViewId        = "chat-messages"
  private val eventSourceListenerId = "chat-event-source-listener"

  def view(chatPostUrl: String) =
    div(
      cls := "rounded-box pl-5 md:col-span-2 border border-bg-base-200 shadow-xl",
      div(
        h2(
          cls := "text-sm text-center font-bold pt-2 tracking-widest",
          "Workbench",
        ),
      ),
      messages(),
      chatForm(postUrl = chatPostUrl),
    )

  def chatForm(
    postUrl: String,
  ) =
    form(
      cls         := "mr-5 pb-5",
      `hx-post`   := postUrl,
      `hx-target` := s"#$messagesViewId",
      `hx-swap`   := "beforeend scroll:bottom",
    )(
      div(
        input(
          cls         := "input input-bordered w-full p-5",
          `type`      := "text",
          name        := "content",
          placeholder := "Type a query to the chatbot",
        ),
      ),
    )

  def messages() =
    div(
      cls := "py-5 pr-5 md:h-[calc(100dvh-16rem)] md:overflow-y-scroll",
      id  := messagesViewId,
    )(
      div(
        cls := "chat chat-start",
        div(cls := "chat-bubble chat-bubble-primary", "Hello, how can I help you?"),
      ),
    )

  def responseMessage(
    queryId: QueryId,
    query: ChatQuery,
    sseUrl: String,
    queryResponseEvent: String,
    queryCloseEvent: String,
  ) =
    val chatBubbleId = s"chat-bubble-$queryId"

    div(
      div(
        cls           := "chat chat-end",
        div(cls := "chat-bubble chat-bubble-secondary", query.content),
      ),
      div(
        id            := eventSourceListenerId,
        `hx-ext`      := "sse",
        `sse-connect` := sseUrl,
        `sse-swap`    := queryResponseEvent,
        `sse-close`   := queryCloseEvent,
        `hx-swap`     := "beforeend scroll:bottom",
        `hx-target`   := s"#$chatBubbleId",
      ),
      div(
        cls           := "chat chat-start",
        div(
          cls := "chat-bubble chat-bubble-primary",
          id  := chatBubbleId,
        )(),
      ),
    )

  def responseClearEventSourceListener() =
    // clear the event source listener, ensuring that the browser won't be reconnecting in case of any issues
    // sse-close is not always enough
    div(id := eventSourceListenerId, `hx-swap-oob` := "true")

  def responseChunk(content: String) =
    span(sanitizeChunk(content))

  private def sanitizeChunk(input: String) =
    // TODO: this could be potentially dangerous, use a proper HTML sanitizer
    raw(
      input
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll("\"", "&quot;")
        .replaceAll("'", "&#x27;")
        .replaceAll("/", "&#x2F;")
        .replaceAll("\n", br().render),
    )
