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
import fs2.io.net.Network
import org.typelevel.ci.CIString

object LiveServer
    extends CommandIOApp(
      name = "live server",
      header = "Purely functional live server"
    ) {

  case class Cli(
      host: String,
      port: Int,
      wait0: Int,
      entryFile: fs2.io.file.Path,
      ignore: Option[List[String]]
  )

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
      entryFile: fs2.io.file.Path
  )(using F: Concurrent[F], C: Console[F])
      extends Http4sDsl[F] {
    private val static: HttpRoutes[F] = HttpRoutes.of[F] {

      case GET -> Root / fileName =>
        C.print(s"$fileName") *> StaticFile
          .fromPath[F](fs2.io.file.Path(fileName))
          .getOrElseF(NotFound())

      case GET -> Root =>
        for {

          indexRsp <- StaticFile.fromPath[F](entryFile).getOrElseF(NotFound())

          injected <- StaticFile
            .fromPath[F](fs2.io.file.Path("injected.html"))
            .getOrElseF(NotFound())

          _ <- C.println(injected.entity.length)

          _ <- C.println(indexRsp.entity.length)

          indexBody <- indexRsp.body
            .through(fs2.text.utf8.decode)
            .compile
            .lastOrError

          injectedBody <- injected.body
            .through(fs2.text.utf8.decode)
            .compile
            .lastOrError

          index <- F.fromOption(
            for {
              ix <- indexBody.indexOf("</html>") match {
                case -1 => None
                case ix => ix.some
              }
            } yield indexBody.patch(ix, injectedBody, 0),
            new RuntimeException("Failed to serve index.html")
          )

          foo = indexRsp.withEntity(index).withContentType(`Content-Type`())

          _ <- C.println(foo.entity.length)

        } yield foo
    }

    val routes = static
  }

  def server[F[_]: Files: Network](cli: Cli)(using
      F: Async[F],
      C: Console[F]
  ): Resource[F, Server] = {
    implicit val loggerFactory: LoggerFactory[F] = Slf4jFactory.create[F]
    for {
      host <-
        F.fromOption(
          Host.fromString(cli.host),
          new IllegalArgumentException("Invalid host")
        ).toResource

      port <-
        F.fromOption(
          Port.fromInt(cli.port),
          new IllegalArgumentException("Invalid port")
        ).toResource

      wsOut <- cats.effect.std.Queue.unbounded[F, String].toResource

      doNotWatch = (p: fs2.io.file.Path) =>
        F.pure {
          cli.ignore
            .fold(false :: Nil)(rgxs =>
              rgxs.map(rgx => rgx.r.findFirstIn(p.toString).isDefined)
            )
            .reduce(_ || _)
        }
        // F.pure(p.names.exists(_.toString.startsWith(".")))

      _ <- Files[F].currentWorkingDirectory.flatMap { cwd =>
        Files[F]
          .watch(cwd)
          .debounce(cli.wait0.milliseconds)
          .map {
            case Event.Created(p, _)     => p.some
            case Event.Deleted(p, _)     => p.some
            case Event.Modified(p, _)    => p.some
            case Event.Overflow(_)       => None
            case Event.NonStandard(_, p) => p.some
          }
          .evalFilterNot(pMaybe => pMaybe.existsM(doNotWatch))
          .evalMap { pMaybe =>
            wsOut.offer("reload") *>
              C.println(
                s"""->>${AnsiColor.CYAN}Changes detected ${pMaybe
                    .map(p => s"at `$p`")
                    .getOrElse("")}${AnsiColor.RESET}"""
              )

          }
          .compile
          .drain
      }.background

      httpRoutes <-
        middleware.CORS.policy
          .withAllowOriginAll(new StaticFileServer[F](cli.entryFile).routes)
          .toResource

      server <- EmberServerBuilder
        .default[F]
        .withHost(host)
        .withPort(port)
        .withHttpWebSocketApp(wsb =>
          (new Websocket[F](wsb, wsOut).routes <+> httpRoutes).orNotFound
        )
        .build
        .evalTap { _ =>
          Files[F].currentWorkingDirectory.flatMap { cwd =>
            C.println(
              s"""|${AnsiColor.MAGENTA}->>Live server of $cwd started at: 
              |http://$host:$port${AnsiColor.RESET}""".stripMargin
            ) *>
              C.println(
                s"""${AnsiColor.RED}->>Ready to watch changes${AnsiColor.RESET}""".stripMargin
              )
          }
        }

    } yield server
  }

  override def main: Opts[IO[ExitCode]] = {

    val host = Opts.option[String]("host", "Host").withDefault("127.0.0.1")

    val port = Opts.option[Int]("port", "Port").withDefault(8080)

    val wait0 =
      Opts.option[Int]("wait", "Wait before reload (ms)").withDefault(1000)

    val entryFile = Opts
      .option[String]("entry-file", "Index html to be served as entry file")
      .withDefault("index.html")
      .map(ef => fs2.io.file.Path(ef))

    val ignore = Opts
      .options[String]("ignore", "List of path regex to not watch")
      .map(_.toList)
      .orNone

    val cli = (host, port, wait0, entryFile, ignore).mapN(Cli.apply)

    val app = cli.map(cl =>
      server[IO](cl).use(_ => IO.never[Unit]).as(ExitCode.Success)
    )

    app

  }
}
