package supportbot

import scalatags.Text.Modifier
import scalatags.Text.all.*
import scalatags.Text.TypedTag

trait HtmxView:
  opaque type HtmxAttr <: Attr = Attr

  lazy val `hx-post`: HtmxAttr     = attr("hx-post")
  lazy val `hx-swap`: HtmxAttr     = attr("hx-swap")
  lazy val `hx-target`: HtmxAttr   = attr("hx-target")
  lazy val `hx-on:click`: HtmxAttr = attr("hx-on:click")
  lazy val `hx-delete`: HtmxAttr   = attr("hx-delete")
  lazy val `hx-boost`: HtmxAttr    = attr("hx-boost")
  lazy val `hx-ext`: HtmxAttr      = attr("hx-ext")
  lazy val `hx-encoding`: HtmxAttr = attr("hx-encoding")
  lazy val `hx-swap-oob`: HtmxAttr = attr("hx-swap-oob")
  lazy val `sse-connect`: HtmxAttr = attr("sse-connect")
  lazy val `sse-swap`: HtmxAttr    = attr("sse-swap")
  lazy val `sse-close`: HtmxAttr   = attr("sse-close")
  
  // html elements not in scalatags
  lazy val dialog = tag("dialog")

  // custom elements
  lazy val fileUploader = tag("file-uploader")
  // lazy val themeController = tag("theme-controller")

  def appLink(path: String, child: Modifier, attrs: Modifier*): TypedTag[String] =
    val allAttrs = Seq(
      attr("href") := path,
      `hx-boost`   := "true",
    ) ++ attrs

    a(allAttrs*)(child)
