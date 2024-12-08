package supportbot
package viewpartials

import scalatags.Text.all.*

object ModalView extends HtmxView:
  final case class Modal(
    button: Modifier,
    window: Modifier,
  )

  def view(
    modalId: String,
    buttonTitle: String,
    modalTitle: String,
    modalContent: Modifier,
    buttonExtraClasses: Vector[String] = Vector.empty,
  ): Modal =
    Modal(
      button = button(
        cls     := "btn " ++ buttonExtraClasses.mkString(" "),
        onclick := s"$modalId.showModal()",
        buttonTitle,
      ),
      window = dialog(
        id  := modalId,
        cls := "modal modal-bottom sm:modal-middle",
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
