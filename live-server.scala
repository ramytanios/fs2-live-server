//> using toolkit typelevel:latest
//> using dep org.http4s::http4s-ember-server:1.0.0-M40
//> using dep org.http4s::http4s-dsl:1.0.0-M40

import cats.effect.kernel.Async
import cats.effect.kernel.Concurrent
import fs2.io.file.Files
import org.http4s.server.websocket.WebSocketBuilder2
import cats.effect.std.Queue
import org.http4s.dsl.Http4sDsl
import org.http4s.HttpRoutes
import org.http4s.websocket.WebSocketFrame
import org.http4s.StaticFile
import cats.effect.kernel.Resource
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import org.http4s.ember.server.EmberServerBuilder
import fs2.io.file.Path
import cats.syntax.all._

object LiveServer {

  final class Websocket[F[_]](ws: WebSocketBuilder2[F], sq: Queue[F, String])(
      implicit F: Async[F]
  ) extends Http4sDsl[F] {

    val routes: HttpRoutes[F] = HttpRoutes.of[F] { case GET -> Root =>
      val send: fs2.Stream[F, WebSocketFrame] =
        fs2.Stream
          .fromQueueUnterminated(sq)
          .map(message => WebSocketFrame.Text(message))
      val receive: fs2.Pipe[F, WebSocketFrame, Unit] =
        is => is.evalMap { _ => F.unit }

      ws.build(send, receive)
    }
  }

  final class StaticFileServer[F[_]: Files: Concurrent](entryFile: Option[String]) {
    private val indexHtml: HttpRoutes[F] =
      for {
        injected <- Files[F]
          .readAll(Path("injected.html"))
          .through(fs2.text.utf8Decode)
          .compile
          .lastOrError

        index0 <- StaticFile
          .fromPath(Path(entryFile.getOrElse(".")))
          .getOrElseF(NotFound())

        index <- F.fromOption(
          for {
            htmlBody0 <- index0.split("<html>").lastOption
            htmlBody <- htmlBody0.split("</html>").lastOption
          } yield s"<html>$htmlBody$injected</html>",
          new RuntimeException("Failed to serve index.html")
        )

        route <- index0.flatMap { b => b.body.replace(index) }
      } yield route

    val routes = indexHtml
  }

  def runF[F[_]: Async: Files]: F[Unit] =
    for {
      host <- Resource.eval {
        Async[F].fromOption(
          Host.fromString("localhost"),
          new IllegalArgumentException("Invalid host")
        )
      }

      port <- Resource.eval {
        Async[F].fromOption(
          Port.fromInt(8090),
          new IllegalArgumentException("Invalid port")
        )
      }

      wsOut <- cats.effect.std.Queue.unbounded[F, String]

      _ <- Files[F]
        .watch(fs2.io.file.Path("."))
        .evalMap { _ => wsOut.offer("reload") }
        .compile
        .drain

      allRoutes = (wsb: WebSocketBuilder2) => {
        val ws = new Websocket[F](wsb, wsOut).routes
        val static = new StaticFileServer[F]().routes
        ws <+> static
      }

      server <- EmberServerBuilder
        .default[F]
        .withHost(host)
        .withPort(port)
        .withHttpWebSocketApp(wsb => allRoutes(wsb))

    } yield server
}
