package supportbot
package context

import cats.effect.*
import cats.syntax.all.*
import org.http4s.{scalatags as _, h2 as _, *}
import scalatags.Text.all.*
import scalatags.Text.tags2.{progress}

import context.chat.*

import supportbot.rag.*

object ContextView extends HtmxView:
  private val uploadedFilesListId = "uploaded-files-list"
  private val uploadModalId       = "uploadModal"

  def view(
    context: ContextInfo,
    chatPostUrl: String,
    uploadUrl: String,
    documents: Vector[Document.Info],
    fileFieldName: String,
  )(using AppConfig) = RootLayoutView.view(
    div(
      configMenu(uploadUrl = uploadUrl, documents = documents, fileFieldName = fileFieldName),
      div(cls := "divider", aria.hidden := true, "workbench"),
      ChatView.messages(),
      ChatView.chatForm(postUrl = chatPostUrl),
    ),
  )

  def uploadedDocuments(docs: Vector[Document.Ingested]) =
    ul(
      `hx-swap-oob` := s"beforeend:#$uploadedFilesListId",
      docs.map(ingested => documentItem(ingested.info)),
    )

  def contextsOverview(contexts: Vector[ContextInfo])(using AppConfig) =
    RootLayoutView.view(
      div(
        div(
          cls := "flex justify-between items-center",
          h2(
            cls := "text-2xl p-5",
            "Your contexts",
          ),
          div(
            appLink(
              "/contexts/new",
              cls := "btn btn-primary",
              "Create new",
            ),
          ),
        ),
        div(
          ul(
            cls := "grid grid-cols-1 md:grid-cols-3 gap-4",
            contexts.map: context =>
              li(
                appLink(
                  s"/contexts/${context.id}",
                  div(
                    cls := "card bg-base-200 shadow-lg shadow-lg hover:shadow-xl transition-shadow",
                    div(
                      cls := "card-body",
                      h2(cls := "card-title", context.name),
                      p(context.description),
                    ),
                  ),
                ),
              ),
          ),
        ),
      ),
    )

  private def configMenu(uploadUrl: String, documents: Vector[Document.Info], fileFieldName: String) =
    div(
      cls := "grid grid-cols-1 md:grid-cols-2 gap-4 py-4",
      div(knowledgeBase(uploadUrl = uploadUrl, documents = documents, fileFieldName = fileFieldName)),
      div(promptSettings()),
      div(retrievalSettings()),
    )

  private def retrievalSettings() =
    collapse(
      opened = false,
      collapseTitle = "Retrieval Settings",
      collapseContent = div(
        form(
          cls := "form-control",
          label(
            cls         := "label",
            "Top K",
          ),
          input(
            cls         := "input",
            `type`      := "number",
            placeholder := "Type the number of top K",
          ),
          label(
            cls         := "label",
            "Fragment Lookup Range",
          ),
          input(
            cls         := "input",
            `type`      := "number",
            placeholder := "Type the number of fragment lookup range",
          ),
          button(
            cls         := "btn btn-primary",
            "Save",
          ),
        ),
      ),
    )

  private def promptSettings() =
    collapse(
      opened = false,
      collapseTitle = "Prompt Settings",
      collapseContent = div(
        form(
          cls := "form-control",
          label(
            cls         := "label",
            "Prompt Template",
          ),
          textarea(
            cls         := "textarea h-24",
            placeholder := "Type your prompt template here",
          ),
          label(
            cls         := "label",
            "Prompt Options",
          ),
          textarea(
            cls         := "textarea h-24",
            placeholder := "Type your prompt options here",
          ),
          label(
            cls         := "label",
            "Prompt Options",
          ),
          textarea(
            cls         := "textarea h-24",
            placeholder := "Type your prompt options here",
          ),
          button(
            cls         := "btn btn-primary",
            "Save",
          ),
        ),
      ),
    )

  private def documentItem(document: Document.Info) =
    li(
      a(
        documentIcon(),
        s"${document.name} - v${document.version}",
      ),
    )

  private def knowledgeBase(
    uploadUrl: String,
    fileFieldName: String,
    documents: Vector[Document.Info],
  ) =
    collapse(
      opened = true,
      collapseTitle = "Knowledge Base",
      collapseContent = div(
        h3("Files"),
        ul(
          id  := uploadedFilesListId,
          cls := "menu menu-xs bg-base-200 rounded-lg w-full max-w-s",
          documents.map(documentItem),
        ),
        modal(
          modalId = uploadModalId,
          buttonTitle = "Upload more",
          modalTitle = "Upload your files",
          modalContent = uploadForm(
            uploadUrl = uploadUrl,
            fileFieldName = fileFieldName,
          ),
          buttonExtraClasses = Vector("btn-secondary block ml-auto"),
        ),
      ),
    )

  private def modal(
    modalId: String,
    buttonTitle: String,
    modalTitle: String,
    modalContent: Modifier,
    buttonExtraClasses: Vector[String] = Vector.empty,
  ) =
    Vector(
      button(
        cls     := "btn " ++ buttonExtraClasses.mkString(" "),
        onclick := s"$modalId.showModal()",
        buttonTitle,
      ),
      dialog(
        id      := modalId,
        cls     := "modal modal-bottom sm:modal-middle",
        div(
          cls       := "modal-box",
          form(
            method := "dialog",
            button(
              cls        := "btn btn-sm btn-circle btn-ghost absolute right-2 top-2",
              aria.label := "close",
              "✕",
            ),
          ),
          h3(cls   := "text-lg font-bold", modalTitle),
          modalContent,
        ),
        form(method := "dialog", cls := "modal-backdrop", button("close")),
      ),
    )

  // https://uploadcare.com/blog/how-to-make-a-drag-and-drop-file-uploader/
  // https://http4s.org/v1/docs/multipart.html
  private def uploadForm(
    uploadUrl: String,
    fileFieldName: String,
  )   =
    fileUploader(
      attr("allowed-types")   :=
        Vector(
          "application/pdf",
          "text/plain",
          "text/html",
          "application/msword",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
          "application/epub+zip",
        ).mkString(","),
      attr("max-size")        := "1073741824", // 1GiB
      attr("upload-url")      := uploadUrl,
      attr("file-field-name") := fileFieldName,
      attr("modal-id")        := uploadModalId,
    )

  private def collapse(
    collapseTitle: Modifier,
    collapseContent: Modifier,
    opened: Boolean = false,
  ) =
    val checkedAttr = Option.when(opened)(checked := true)

    div(
      cls := "collapse collapse-arrow border-base-300 bg-base-200 border",
      input(
        `type` := "checkbox",
        cls    := "peer w-full",
        checkedAttr,
      ),
      div(
        cls    := "collapse-title text-xl font-medium",
        collapseTitle,
      ),
      div(
        cls    := "collapse-content",
        collapseContent,
      ),
    )

  private def documentIcon() =
    import scalatags.Text.svgTags.{attr as _, *}
    import scalatags.Text.svgAttrs.*

    svg(
      xmlns                := "http://www.w3.org/2000/svg",
      aria.hidden          := true,
      fill                 := "none",
      viewBox              := "0 0 24 24",
      attr("stroke-width") := "1.5",
      stroke               := "currentColor",
      cls                  := "h-4 w-4",
      path(
        attr("stroke-linecap")  := "round",
        attr("stroke-linejoin") := "round",
        d                       := "M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m0 12.75h7.5m-7.5 3H12M10.5 2.25H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z",
      ),
    )
