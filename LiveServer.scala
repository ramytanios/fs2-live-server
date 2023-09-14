//> using toolkit typelevel:latest
//> using dep org.http4s::http4s-ember-server:1.0.0-M40
//> using dep org.http4s::http4s-dsl:1.0.0-M40
//> using dep com.monovore::decline-effect:2.4.1

import cats.effect.kernel.*
import fs2.io.file.Files
import org.http4s.server.websocket.WebSocketBuilder2
import cats.effect.std.Queue
import org.http4s.websocket.WebSocketFrame
import org.http4s.server.middleware
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import fs2.io.file.Path
import cats.syntax.all.*
import org.typelevel.log4cats.LoggerFactory
import org.http4s.dsl.io.*
import org.typelevel.log4cats.slf4j.Slf4jFactory
import cats.effect.IO
import org.http4s._
import org.http4s.dsl._
import org.http4s.dsl.impl.Responses.NotFoundOps
import cats.effect.std.Console
import scala.io.AnsiColor
import scala.concurrent.duration.*
import cats.effect.implicits.*
import org.http4s.server.Server
import fs2.io.file.Watcher.Event
import com.monovore.decline._
import com.monovore.decline.effect._
import cats.effect.ExitCode

object LiveServer
    extends CommandIOApp(
      name = "live server",
      header = "Purely functional live server"
    ) {

  final class Websocket[F[_]](ws: WebSocketBuilder2[F], sq: Queue[F, String])(
      using F: Async[F]
  ) extends Http4sDsl[F] {
    val routes: HttpRoutes[F] = HttpRoutes.of[F] { case GET -> Root / "ws" =>
      val send: fs2.Stream[F, WebSocketFrame] =
        fs2.Stream
          .fromQueueUnterminated(sq)
          .map(message => WebSocketFrame.Text(message))
      val receive: fs2.Pipe[F, WebSocketFrame, Unit] =
        is => is.evalMap { _ => F.unit }
      ws.build(send, receive)
    }
  }

  final class StaticFileServer[F[_]: Files: LoggerFactory](
      entryFile: Option[String]
  )(using F: Concurrent[F], C: Console[F])
      extends Http4sDsl[F] {
    private val static: HttpRoutes[F] = HttpRoutes.of[F] {
      case GET -> Root =>
        for {
          injected <- Files[F]
            .readAll(fs2.io.file.Path("injected.html"))
            .through(fs2.text.utf8Decode)
            .compile
            .lastOrError

          indexRsp <- StaticFile
            .fromPath[F](fs2.io.file.Path(entryFile.getOrElse("index.html")))
            .getOrElseF(NotFound())

          indexBody <- indexRsp.body
            .through(fs2.text.utf8Decode)
            .compile
            .lastOrError

          index <- F.fromOption(
            for {
              ix <- indexBody.indexOf("</html>") match {
                case -1 => None
                case ix => ix.some
              }
            } yield indexBody.patch(ix, injected, 0),
            new RuntimeException("Failed to serve index.html")
          )

        } yield indexRsp.copy(entity =
          Entity.stream[F](
            fs2.Stream.emit(index).covary[F].through(fs2.text.utf8Encode)
          )
        )

      case GET -> Root / fileName =>
        StaticFile
          .fromPath[F](fs2.io.file.Path(fileName))
          .getOrElseF(NotFound())
    }

    val routes = static
  }

  def server[F[_]: Files](ef: Option[String])(using
      F: Async[F],
      C: Console[F]
  ): Resource[F, Server] = {
    implicit val loggerFactory: LoggerFactory[F] = Slf4jFactory.create[F]
    for {
      host <-
        F.fromOption(
          Host.fromString("127.0.0.1"),
          new IllegalArgumentException("Invalid host")
        ).toResource

      port <-
        F.fromOption(
          Port.fromInt(8080),
          new IllegalArgumentException("Invalid port")
        ).toResource

      wsOut <- cats.effect.std.Queue.unbounded[F, String].toResource

      isDotfile = (p: fs2.io.file.Path) => p.fileName.startsWith(".")

      _ <- Files[F]
        .watch(fs2.io.file.Path("."))
        .debounce(1.second)
        .map {
          case Event.Created(p, _)     => p.some
          case Event.Deleted(p, _)     => p.some
          case Event.Modified(p, _)    => p.some
          case Event.Overflow(_)       => None
          case Event.NonStandard(_, p) => p.some
        }
        .filterNot(pMaybe => pMaybe.exists(p => isDotfile(p)))
        .evalMap { pMaybe =>
          wsOut.offer("reload") *>
            C.println(
              s"${AnsiColor.CYAN}Changes detected ${pMaybe.map(p => s"@ `$p`").getOrElse("")}${AnsiColor.RESET}"
            )

        }
        .compile
        .drain
        .background

      httpRoutes <-
        middleware.CORS.policy
          .withAllowOriginAll(new StaticFileServer[F](ef).routes)
          .toResource

      server <- EmberServerBuilder
        .default[F]
        .withHost(host)
        .withPort(port)
        .withHttpWebSocketApp(wsb =>
          (httpRoutes <+> new Websocket[F](wsb, wsOut).routes).orNotFound
        )
        .build
        .evalTap { _ =>
          Files[F].currentWorkingDirectory.flatMap { cwd =>
            C.println(
              s"${AnsiColor.MAGENTA}live server of $cwd started @ http://$host:$port${AnsiColor.RESET}"
            )
          }
        }

    } yield server
  }

  override def main: Opts[IO[ExitCode]] = {

    case class Cli(entryFile: Option[String])

    val cli = Opts
      .option[String]("entry-file", "Index html to be served as entry file")
      .orNone
      .map(ef => Cli(ef))

    val app = cli.map(cl =>
      server[IO](cl.entryFile).use(_ => IO.never[Unit]).as(ExitCode.Success)
    )

    app

  }
}
