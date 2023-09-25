import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.kernel.Concurrent
import fs2.io.file.Files
import org.http4s.server.websocket.WebSocketBuilder2
import cats.effect.std.Queue
import org.http4s.websocket.WebSocketFrame
import org.http4s.server.middleware
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import fs2.io.file.{Path => Fs2Path}
import cats.syntax.all.*
import org.http4s.dsl.io.*
import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.*
import cats.effect.std.Console
import scala.io.AnsiColor
import scala.concurrent.duration.*
import cats.effect.implicits.*
import org.http4s.server.Server
import fs2.io.file.Watcher.Event
import com.monovore.decline.*
import com.monovore.decline.effect.*
import cats.effect.ExitCode
import fs2.io.net.Network
import org.typelevel.ci.CIString
import org.http4s.headers.`Content-Type`
import cats.data.Kleisli
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.Uri.{Path => UriPath}
import cats.effect.std.Random

object LiveServer
    extends CommandIOApp(
      name = "live server",
      header = "Purely functional live server"
    ) {

  case class Cli(
      host: Host,
      port: Port,
      wait0: FiniteDuration,
      entryFile: Fs2Path,
      ignore: Option[List[String]],
      watch: Option[List[Fs2Path]],
      proxy: Option[(UriPath.Segment, Uri)],
      cors: Boolean,
      verbose: Boolean
  ) {
    def withPort(port: Port) = this.copy(port = port)
  }

  // websocket connection
  final class Websocket[F[_]](wsb: WebSocketBuilder2[F], sq: Queue[F, String])(
      using F: Async[F]
  ) extends Http4sDsl[F] {
    val routes: HttpRoutes[F] = HttpRoutes.of[F] { case GET -> Root / "ws" =>
      val send: fs2.Stream[F, WebSocketFrame] =
        fs2.Stream
          .fromQueueUnterminated(sq)
          .map(message => WebSocketFrame.Text(message))

      val receive: fs2.Pipe[F, WebSocketFrame, Unit] =
        is => is.evalMap { _ => F.unit }

      wsb.build(send, receive)
    }
  }

  // script injector
  object ScriptInjector {
    def apply(html: String, script: String): Option[String] =
      html.indexOf("</html>") match {
        case -1 => None
        case ix => html.patch(ix, script, 0).some
      }
  }

  // static file server
  final class StaticFileServer[F[_]: Files](
      entryFile: Fs2Path
  )(using F: Concurrent[F], C: Console[F])
      extends Http4sDsl[F] {

    private val injectedPath = Fs2Path("injected.html")

    private def readFileF(path: Fs2Path): F[String] =
      Files[F].readUtf8(path).compile.lastOrError

    val routes: HttpRoutes[F] = HttpRoutes.of[F] {
      case GET -> Root =>
        for {
          index <- readFileF(entryFile)
          script <- readFileF(injectedPath)
          index0 <- F.fromOption(
            ScriptInjector(index, script),
            new RuntimeException("Failed to serve index.html")
          )
          mediaType <- F.fromOption(
            MediaType.forExtension("html"),
            new RuntimeException("Invalid media type")
          )
        } yield Response[F]()
          .withEntity(index0)
          .withContentType(`Content-Type`(mediaType, Charset.`UTF-8`))

      case GET -> Root / fileName =>
        StaticFile
          .fromPath[F](Fs2Path(fileName))
          .getOrElseF(NotFound())
    }
  }

  // proxy middleware
  object ProxyMiddleware {
    def default[F[_]](
        httpApp: HttpRoutes[F],
        path: UriPath.Segment,
        uri: Uri,
        client: Client[F]
    )(using F: Async[F]): HttpRoutes[F] =
      Kleisli { req =>
        req.uri match {
          case Uri(_, _, path0, _, _)
              if path0.segments.headOption.exists(_ == path) =>
            cats.data.OptionT.liftF({
              val newReq = req.withUri(uri.withPath(path0))
              client.stream(newReq).compile.lastOrError
            })
          case _ => httpApp(req)
        }
      }
  }

  def runServerImpl[F[_]: Files: Network](
      cli: Cli
  )(using F: Async[F], C: Console[F]): F[Unit] = 
    for {

      cwd <- Files[F].currentWorkingDirectory

      eventQ <- Queue.unbounded[F, Event]

      wsOut <- Queue.unbounded[F, String]

      doNotWatchPath = (p: Fs2Path) =>
        F.pure {
          cli.ignore
            .fold(false :: Nil)(rgxs =>
              rgxs.map(rgx => rgx.r.findFirstIn(p.toString).isDefined)
            )
            .reduce(_ || _)
        }

      pathFromEvent = (ev: Event) =>
        ev match {
          case Event.Created(p, _)     => p.some
          case Event.Deleted(p, _)     => p.some
          case Event.Modified(p, _)    => p.some
          case Event.Overflow(_)       => None
          case Event.NonStandard(_, p) => p.some
        }

      _ <- cli.watch
        .getOrElse(cwd :: Nil)
        .map(path =>
          Files[F]
            .watch(path)
            .evalMap(eventQ.offer)
            .compile
            .drain
        )
        .parSequence
        .start

      _ <- fs2.Stream
        .fromQueueUnterminated(eventQ)
        .map(pathFromEvent)
        .evalFilterNot(_.existsM(doNotWatchPath))
        .debounce(cli.wait0)
        .evalMap { pMaybe =>
          wsOut.offer("reload") *> C.println(
            s"""${AnsiColor.CYAN}Changes detected ${pMaybe
                .map(p => s"at `$p`")
                .getOrElse("")}${AnsiColor.RESET}"""
          )

        }
        .compile
        .drain
        .start

      app <- {
        val sf = new StaticFileServer[F](cli.entryFile).routes
        for {
          // proxy middleware
          app0 <- cli.proxy.fold(F.pure(sf)) { case (path, url) =>
            EmberClientBuilder.default[F].build.use { client =>
              F.delay(ProxyMiddleware.default(sf, path, url, client))
            }
          }
          // CORS middleware
          app1 =
            if (cli.cors) { middleware.CORS.policy.withAllowOriginAll(sf) }
            else app0
          // logging middleware
          app2 =
            if (cli.verbose) {
              middleware.Logger.httpRoutes[F](
                logHeaders = true,
                logBody = true,
                logAction = ((msg: String) => C.println(msg)).some
              )(app1)
            } else app1

        } yield app2
      }

      _ <- EmberServerBuilder
        .default[F]
        .withHost(cli.host)
        .withPort(cli.port)
        .withHttpWebSocketApp(wsb =>
          (new Websocket[F](wsb, wsOut).routes <+> app).orNotFound
        )
        .build
        .evalTap { _ =>
          C.println(
            s"""|${AnsiColor.MAGENTA}Live server of $cwd started at: 
              |http://${cli.host}:${cli.port}${AnsiColor.RESET}""".stripMargin
          ) *> C.println(
            s"""${AnsiColor.RED}Ready to watch changes${AnsiColor.RESET}"""
          )
        }
        .use(_ => F.never[Unit])

    } yield ()

  def runServer[F[_]: Files: Network](
      cli: Cli
  )(using F: Async[F], C: Console[F]): F[Unit] =
    runServerImpl(cli).handleErrorWith { case th =>
      for {
        _ <- C.println(
          s"""${AnsiColor.RED}Failed to start server. Perhaps port ${cli.port} already in use.
          Attempt to find other port ..${AnsiColor.RESET}"""
        )
        rg <- Random.scalaUtilRandom[F]
        newPortMaybe <- rg.betweenInt(1024, 65535).map(Port.fromInt)
        newPort <- F.fromOption(
          newPortMaybe,
          new IllegalArgumentException("Bad port")
        )
        server <- runServer(cli.withPort(newPort))
      } yield server
    }

  override def main: Opts[IO[ExitCode]] = {

    val host = Opts
      .option[String]("host", "Host")
      .withDefault("127.0.0.1")
      .mapValidated(host => Host.fromString(host).toValidNel("Invalid host"))

    val port = Opts
      .option[Int]("port", "Port")
      .withDefault(8080)
      .mapValidated(port => Port.fromInt(port).toValidNel("Invalid port"))

    val wait0 =
      Opts
        .option[Int]("wait", "Wait before reload (ms)")
        .withDefault(1000)
        .map(_.milliseconds)

    val entryFile = Opts
      .option[String]("entry-file", "Index html to be served as entry file")
      .withDefault("index.html")
      .map(ef => Fs2Path(ef))

    val ignore = Opts
      .options[String]("ignore", "List of path regex to not watch")
      .map(_.toList)
      .orNone

    val watch = Opts
      .options[String]("watch", "List of paths to watch")
      .map(_.toList.map(p => Fs2Path(p)))
      .orNone

    val proxy = Opts
      .option[String]("proxy", "Path:URL proxy")
      .mapValidated(proxy => {
        val sp = proxy.split(":")
        (
          sp.headOption.map(UriPath.Segment),
          Uri.fromString(sp.tail.mkString(":")).toOption
        ).tupled.toValidNel("Bad proxy settings")
      })
      .orNone

    val cors =
      Opts.flag("cors", "Allow any origin requests").orFalse

    val verbose =
      Opts.flag("verbose", "Logs all requests and responses", "V").orFalse

    val cli =
      (
        host,
        port,
        wait0,
        entryFile,
        ignore,
        watch,
        proxy,
        cors,
        verbose
      ).mapN(
        Cli.apply
      )

    val app = cli.map(cl => runServer[IO](cl).as(ExitCode.Success))
    app

  }
}
