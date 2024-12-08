package supportbot
package integrations
package slack

import cats.*
import cats.data.*
import cats.effect.*
import cats.syntax.all.*
import scala.util.control.NoStackTrace
import org.http4s.*
import org.http4s.server.AuthMiddleware
import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.syntax.*
import org.typelevel.ci.CIString
import java.time.Instant

import com.slack.api.app_backend.SlackSignature
import com.slack.api.app_backend.SlackSignature.HeaderNames.*

object SlackSignatureVerifier:
  def middleware[F[_]: Async: Logger](signingSecret: String): AuthMiddleware[F, AuthInfo] =

    // TODO: reimplement the slack signature verifier in Scala and remove the dep on slack_app_backend
    val slackSignatureVerifier =
      SlackSignature.Verifier(SlackSignature.Generator(signingSecret))

    AuthMiddleware(
      authUser = Kleisli(validateSignature[F](slackSignatureVerifier)),
      onFailure = unauthorized,
    )

  private def unauthorized[F[_]: Applicative]: AuthedRoutes[String, F] =
    Kleisli(_ => OptionT.pure(Response[F](Status.Unauthorized)))

  private def validateSignature[F[_]: Async: Logger](
    verifier: SlackSignature.Verifier,
  )(request: Request[F]): F[Either[String, AuthInfo]] =
    for
      _         <- info"Received slack request: $request"
      timestamp <- getHeaderOrRaiseError(request, X_SLACK_REQUEST_TIMESTAMP)
      signature <- getHeaderOrRaiseError(request, X_SLACK_SIGNATURE)
      body      <- request.as[String]
      result    <-
        if verifier.isValid(timestamp, body, signature)
        then
          info"Request with signature $signature is valid".as:
            AuthInfo(
              // assuming the the timestamp is a unix timestamp and since signature is valid we can parse it just fine
              timestamp = Instant.ofEpochSecond(timestamp.toLong),
            ).asRight[String]
        else
          error"Request with signature $signature is invalid".as:
            SlackAuthError
              .BadSignature(timestamp, body, signature)
              .toString
              .asLeft[AuthInfo]
    yield result

  private def getHeaderOrRaiseError[F[_]: Sync](req: Request[F], headerName: String): F[String] =
    Sync[F].fromOption(
      req.headers.get(CIString(headerName)).map(_.head.value),
      SlackAuthError.MissingHeader(headerName),
    )

final case class AuthInfo(timestamp: Instant)

enum SlackAuthError(message: String) extends NoStackTrace:
  case MissingHeader(headerName: String) extends SlackAuthError(s"Missing required header: $headerName")
  case BadSignature(timestamp: String, body: String, signature: String)
      extends SlackAuthError(s"Bad signature: timestamp=$timestamp, body=$body, signature=$signature")

  override def getMessage: String = message
