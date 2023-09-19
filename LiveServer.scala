//> using toolkit typelevel:latest
//> using dep org.http4s::http4s-ember-server:1.0.0-M40
//> using dep org.http4s::http4s-ember-client:1.0.0-M40
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
import org.http4s.headers.`Content-Type`
import cats.data.Kleisli
import java.net.http.HttpClient
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.Uri.Path.Segment

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
      ignore: Option[List[String]],
      watch: Option[List[String]],
      proxy: Option[String]
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

  object ScriptInjector {
    def apply(html: String, script: String): Option[String] =
      html.indexOf("</script>") match {
        case -1 =>
          html.indexOf("</html>") match {
            case -1 => None
            case ix =>
              html
                .patch(
                  ix,
                  s"""|<script type="text/javascript">
                  |$script
                  |</script>""".stripMargin,
                  0
                )
                .some
          }
        case ix => html.patch(ix, script, 0).some
      }
  }

  final class StaticFileServer[F[_]: Files: LoggerFactory](
      entryFile: fs2.io.file.Path
  )(using F: Concurrent[F], C: Console[F])
      extends Http4sDsl[F] {

    val injectedPath = fs2.io.file.Path("injected.js")

    private val static: HttpRoutes[F] = HttpRoutes.of[F] {
      case GET -> Root / fileName =>
        StaticFile
          .fromPath[F](fs2.io.file.Path(fileName))
          .getOrElseF(NotFound())

      case GET -> Root =>
        for {
          index <- Files[F].readUtf8(entryFile).compile.lastOrError
          script <- Files[F].readUtf8(injectedPath).compile.lastOrError
          index0 <- F.fromOption(
            ScriptInjector(index, script),
            new RuntimeException("Failed to serve index.html")
          )
          mediaType <- F.fromOption(
            MediaType.forExtension("html"),
            new RuntimeException("Invalid media type")
          )
        } yield Response()
          .withEntity(index0)
          .withContentType(`Content-Type`(mediaType, Charset.`UTF-8`))

    }

    val routes = static
  }

  object ProxyMiddleware {

    def default[F[_]: LoggerFactory](
        httpApp: HttpRoutes[F],
        path: String,
        url: String,
        client: Client[F]
    )(using F: Async[F]): HttpRoutes[F] = {
      val newUriMaybe = Uri.fromString(url)
      Kleisli { req =>
        req.uri match {
          case Uri(_, _, path0, _, _)
              if path0.segments.toList.startsWith(Segment(path) :: Nil) =>
            cats.data.OptionT.liftF(
              F.fromEither(newUriMaybe)
                .flatMap(uri => {
                  val newReq = req.withUri(uri.withPath(path0))
                  client.stream(newReq).compile.lastOrError
                })
            )
          case _ => httpApp(req)
        }
      }
    }
  }

  def server[F[_]: Files: Network](
      cli: Cli
  )(using F: Async[F], C: Console[F]): Resource[F, Server] = {
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

      doNotWatchPath = (p: fs2.io.file.Path) =>
        F.pure {
          cli.ignore
            .fold(false :: Nil)(rgxs =>
              rgxs.map(rgx => rgx.r.findFirstIn(p.toString).isDefined)
            )
            .reduce(_ || _)
        }

      pathFromEvent = (e: Event) =>
        e match {
          case Event.Created(p, _)     => p.some
          case Event.Deleted(p, _)     => p.some
          case Event.Modified(p, _)    => p.some
          case Event.Overflow(_)       => None
          case Event.NonStandard(_, p) => p.some
        }

      eventQ <- cats.effect.std.Queue.unbounded[F, Event].toResource

      cwd <- Files[F].currentWorkingDirectory.toResource

      _ <- cli.watch
        .fold(cwd :: Nil)(_.map(p => fs2.io.file.Path(p)))
        .map(p => Files[F].watch(p).evalMap(eventQ.offer).compile.drain)
        .parSequence
        .background

      _ <- fs2.Stream
        .fromQueueUnterminated(eventQ)
        .debounce(cli.wait0.milliseconds)
        .map(pathFromEvent)
        .evalFilterNot(_.existsM(doNotWatchPath))
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
        .background

      app <- middleware.CORS.policy
        .withAllowOriginAll(new StaticFileServer[F](cli.entryFile).routes)
        .flatMap { app =>
          cli.proxy.fold(F.pure(app))(proxy =>
            F.fromOption(
              for {
                path <- proxy.split(":").headOption
                url <- proxy.split(":").tail.mkString(":").some
              } yield (path, url),
              new RuntimeException("Bad proxy settings")
            ).flatMap { case (path, url) =>
              EmberClientBuilder.default[F].build.use { client =>
                F.delay(ProxyMiddleware.default(app, path, url, client))
              }
            }
          )
        }.toResource

      server <- EmberServerBuilder
        .default[F]
        .withHost(host)
        .withPort(port)
        .withHttpWebSocketApp(wsb =>
          (new Websocket[F](wsb, wsOut).routes <+> app).orNotFound
        )
        .build
        .evalTap { _ =>
          C.println(
            s"""|${AnsiColor.MAGENTA}->>Live server of $cwd started at: 
              |http://$host:$port${AnsiColor.RESET}""".stripMargin
          ) *> C.println(
            s"""${AnsiColor.RED}->>Ready to watch changes${AnsiColor.RESET}""".stripMargin
          )
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

    val watch = Opts
      .options[String]("watch", "List of paths to watch")
      .map(_.toList)
      .orNone

    val proxy = Opts
      .option[String]("proxy", "Proxy routes to URL, format ROUTE:URL")
      .orNone
    // .validate

    val cli =
      (host, port, wait0, entryFile, ignore, watch, proxy).mapN(Cli.apply)

    val app = cli.map(cl =>
      server[IO](cl).use(_ => IO.never[Unit]).as(ExitCode.Success)
    )

    app

  }
}
