package supportbot
package context

import cats.effect.*
import cats.syntax.all.*
import org.http4s.{scalatags as _, h2 as _, *}
import scalatags.Text.all.*
import scalatags.Text.tags2.{progress, details, summary}

import context.chat.*

import supportbot.rag.*

object ContextView extends HtmxView:
  private val uploadedFilesListId = "uploaded-files-list"
  private val uploadModalId       = "uploadModal"

  def view(
    contextInfo: ContextInfo,
    chatPostUrl: String,
    contextUpdateUrl: String,
    uploadUrl: String,
    documents: Vector[Document.Info],
    fileFieldName: String,
  )(using AppConfig) = RootLayoutView.view(
    div(
      cls := "grid grid-cols-1 md:grid-cols-2 gap-6",
      div(
        configMenu(
          uploadUrl = uploadUrl,
          contextUpdateUrl = contextUpdateUrl,
          documents = documents,
          fileFieldName = fileFieldName,
          contextInfo = contextInfo,
        ),
      ),
      div(
        div(cls := "divider", aria.hidden := true, "workbench"),
        ChatView.messages(),
        ChatView.chatForm(postUrl = chatPostUrl),
      ),
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

  private def configMenu(
    uploadUrl: String,
    contextUpdateUrl: String,
    documents: Vector[Document.Info],
    fileFieldName: String,
    contextInfo: ContextInfo,
  ) =
    div(
      role := "tablist",
      cls  := "tabs tabs-lifted",
      tab(
        "Knowledge Base",
        knowledgeBase(uploadUrl = uploadUrl, documents = documents, fileFieldName = fileFieldName),
        checked = true,
      ),
      tab("Context Settings", contextSettings(contextInfo = contextInfo, contextUpdateUrl = contextUpdateUrl)),
    )

  private def tab(name: String, content: Modifier, checked: Boolean = false) =
    Seq(
      input(
        `type`             := "radio",
        attr("name")       := "my_tabs_2",
        role               := "tab",
        cls                := "tab bg-inherit min-w-36 focus:[box-shadow:none] checked:[background-image:none]",
        attr("aria-label") := name,
        Option.when(checked)(attr("checked") := "checked"),
      ),
      div(role             := "tabpanel", cls := "tab-content bg-base-100 border-base-300 rounded-box p-2 md:p-6", content),
    )

  // private def retrievalSettings() =
  //   collapse(
  //     opened = false,
  //     collapseTitle = "Retrieval Settings",
  //     collapseContent = div(
  //       form(
  //         cls := "form-control",
  //         label(
  //           cls         := "label",
  //           "Top K",
  //         ),
  //         input(
  //           cls         := "input",
  //           `type`      := "number",
  //           placeholder := "Type the number of top K",
  //         ),
  //         label(
  //           cls         := "label",
  //           "Fragment Lookup Range",
  //         ),
  //         input(
  //           cls         := "input",
  //           `type`      := "number",
  //           placeholder := "Type the number of fragment lookup range",
  //         ),
  //         button(
  //           cls         := "btn btn-primary",
  //           "Save",
  //         ),
  //       ),
  //     ),
  //   )

  private def contextSettings(
    contextInfo: ContextInfo,
    contextUpdateUrl: String,
  ) =
    val promptTemplateJson =
      contextInfo.promptTemplate.asJson(indentStep = 2).combineAll

    // println(
    //   "contextInfo" -> contextInfo,
    // )

    div(
      form(
        `hx-post` := contextUpdateUrl,
        `hx-swap` := "none",
        div(
          cls := "grid grid-cols-1 md:grid-cols-2 gap-2",
          formInput(
            labelValue = "Name",
            fieldName = "name",
            value = contextInfo.name,
          ),
          formInput(
            labelValue = "Description",
            fieldName = "description",
            value = contextInfo.description,
          ),
        ),
        formTextarea(
          labelValue = "Prompt Template",
          fieldName = "promptTemplate",
          value = promptTemplateJson,
        ),
        formSelect(
          labelValue = "Chat Model",
          fieldName = "chatModel",
          options = modelOptions(contextInfo.chatModel),
        ),
        formSelect(
          labelValue = "Embeddings Model",
          fieldName = "embeddingsModel",
          options = modelOptions(contextInfo.embeddingsModel),
        ),
        button(
          cls := "btn btn-secondary block ml-auto mt-2",
          "Save",
        ),
      ),
    )

  private def formTextarea(labelValue: String, fieldName: String, value: String) =
    formControl(
      labelValue,
      textarea(
        cls  := "textarea textarea-bordered w-full h-64 bg-base-200",
        name := fieldName,
        value,
      ),
    )

  private def formInput(labelValue: String, fieldName: String, value: String) =
    formControl(
      labelValue,
      input(
        cls           := "input input-bordered w-full bg-base-200",
        name          := fieldName,
        attr("value") := value,
      ),
    )

  final case class SelectOption(label: String, value: String, selected: Boolean = false)

  private def formSelect(labelValue: String, fieldName: String, options: Vector[SelectOption]) =
    formControl(
      labelValue,
      select(
        cls  := "select select-bordered w-full bg-base-200",
        name := fieldName,
        options.map: op =>
          option(
            value    := op.value,
            Option.when(op.selected)(selected := true),
            // selected := op.selected,
            op.label,
          ),
      ),
    )

  private def modelOptions(current: Model) = Model.values.toVector.map: model =>
    SelectOption(
      label = model.name,
      value = model.name,
      selected = model == current,
    )

  private def formControl(labelValue: String, input: Modifier) =
    label(
      cls := "form-control w-full",
      div(
        cls := "label",
        span(
          cls := "label-text",
          labelValue,
        ),
      ),
      input,
    )

  private def documentItem(document: Document.Info) =
    li(
      div(
        span(documentIcon()),
        span(cls := "text-wrap break-all", s"${document.name} - v${document.version}"),
      ),
    )

  private def knowledgeBase(
    uploadUrl: String,
    fileFieldName: String,
    documents: Vector[Document.Info],
  ) =

    val files = div(
      ul(
        cls := "max-h-60 overflow-y-scroll",
        id  := uploadedFilesListId,
        // cls := "menu menu-xs bg-base-200 rounded-lg w-full max-w-s",
        documents.map(documentItem),
      ),
    )

    val uploadFilesButton = modal(
      modalId = uploadModalId,
      buttonTitle = "Upload files",
      modalTitle = "Upload your files",
      modalContent = uploadForm(
        uploadUrl = uploadUrl,
        fileFieldName = fileFieldName,
      ),
      buttonExtraClasses = Vector("btn-secondary block ml-auto"),
    )

    div(
      ul(
        cls := "menu menu-xs bg-base-200 rounded-lg w-full max-w-s mb-4",
        li(
          details(
            // Option.when(documents.length < 30)(attr("open") := true),
            attr("open") := true,
            summary(
              folderIcon(),
              "Files",
            ),
            files,
          ),
        ),
      ),
      uploadFilesButton,
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
  private def uploadForm(uploadUrl: String, fileFieldName: String) =
    fileUploader(
      attr("upload-url")      := uploadUrl,
      attr("file-field-name") := fileFieldName,
      attr("modal-id")        := uploadModalId,
      attr("max-size")        := "1073741824", // 1GiB
      attr("allowed-types")   :=
        Vector(
          // General
          "application/pdf",
          "text/plain",
          "text/html",
          "text/csv",
          "text/xml",
          "application/rtf",
          "application/json",
          "application/xml",
          "application/xhtml+xml",
          // MS
          "application/vnd.ms-excel",
          "application/msword",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
          "application/vnd.openxmlformats-officedocument.presentationml.presentation",
          // OpenOffice
          "application/vnd.ms-powerpoint",
          "application/x-vnd.oasis.opendocument.spreadsheet",
          "application/vnd.oasis.opendocument.spreadsheet",
          "application/vnd.oasis.opendocument.presentation",
          "application/vnd.oasis.opendocument.text",
          // Other
          "application/x-abiword",
          // Ebooks
          "application/epub+zip",
          "application/x-mobipocket-ebook",
        ).mkString(","),
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

  private def folderIcon() =
    import scalatags.Text.svgTags.{attr as _, *}
    import scalatags.Text.svgAttrs.*

    svg(
      xmlns                := "http://www.w3.org/2000/svg",
      fill                 := "none",
      viewBox              := "0 0 24 24",
      attr("stroke-width") := "1.5",
      stroke               := "currentColor",
      cls                  := "h-4 w-4",
      path(
        attr("stroke-linecap")  := "round",
        attr("stroke-linejoin") := "round",
        d                       := "M2.25 12.75V12A2.25 2.25 0 014.5 9.75h15A2.25 2.25 0 0121.75 12v.75m-8.69-6.44l-2.12-2.12a1.5 1.5 0 00-1.061-.44H4.5A2.25 2.25 0 002.25 6v12a2.25 2.25 0 002.25 2.25h15A2.25 2.25 0 0021.75 18V9a2.25 2.25 0 00-2.25-2.25h-5.379a1.5 1.5 0 01-1.06-.44z",
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
