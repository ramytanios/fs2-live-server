import cats.MonadThrow
import cats.data.Kleisli
import cats.data.NonEmptyList
import cats.data.OptionT
import cats.effect.*
import cats.effect.implicits.*
import cats.effect.std.Console
import cats.effect.std.Queue
import cats.effect.std.Random
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.monovore.decline.*
import com.monovore.decline.effect.*
import fs2.io.file.Files
import fs2.io.file.Watcher.Event
import fs2.io.file.{Path => Fs2Path}
import fs2.io.net.Network
import mouse.all.*
import org.http4s.Uri.{Path => UriPath}
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.*
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`
import org.http4s.server.Server
import org.http4s.server.middleware
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.typelevel.ci.CIString

import java.net.BindException
import scala.concurrent.duration.*
import scala.io.AnsiColor

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
      ignore: Option[NonEmptyList[String]],
      watch: Option[List[Fs2Path]],
      proxy: Option[(UriPath.Segment, Uri)],
      cors: Boolean,
      verbose: Boolean
  ) {
    def withPort(port: Port) = this.copy(port = port)

    def ignorePath(p: Fs2Path): Boolean =
      this.ignore
        .fold(false :: Nil)(_.toList.map(_.r.findFirstIn(p.toString).isDefined))
        .reduce(_ || _)

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

      val receive: fs2.Pipe[F, WebSocketFrame, Unit] = _.drain

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
      indexHtml: String
  )(using F: Async[F], C: Console[F])
      extends Http4sDsl[F] {

    private def readFileF(path: Fs2Path): F[String] =
      Files[F].readUtf8(path).compile.onlyOrError

    val routes: HttpRoutes[F] = HttpRoutes.of[F] {
      case GET -> Root =>
        Response[F]()
          .withEntity(indexHtml)
          .withContentType(`Content-Type`(MediaType.text.html, Charset.`UTF-8`))
          .pure[F]

      case GET -> path =>
        StaticFile
          .fromPath[F](Fs2Path(s".${path.toString}"))
          .getOrElseF(NotFound())
    }
  }

  object ErrorLoggingMiddleware {

    def apply[F[_]: MonadThrow: Console](routes: HttpRoutes[F]): HttpRoutes[F] =
      org.http4s.server.middleware.ErrorAction.httpRoutes
        .log(
          routes,
          (t, msg) => Console[F].errorln(s"$msg: $t"),
          (t, msg) => Console[F].errorln(s"$msg: $t")
        )

  }

  object ProxyMiddleware {

    def apply[F[_]](
        path: UriPath.Segment,
        uri: Uri,
        client: Client[F]
    )(httpRoutes: HttpRoutes[F])(using F: Concurrent[F]): HttpRoutes[F] =
      Kleisli { req =>
        req.uri match {
          case Uri(_, _, path0, _, _)
              if path0.segments.headOption.exists(_ == path) =>
            OptionT(
              client.stream(req.withUri(uri.withPath(path0))).compile.last
            )
          case _ => httpRoutes(req)
        }
      }

  }

  def pathFromEvent(ev: Event): Option[Fs2Path] =
    ev match {
      case Event.Created(p, _)     => p.some
      case Event.Deleted(p, _)     => p.some
      case Event.Modified(p, _)    => p.some
      case Event.Overflow(_)       => None
      case Event.NonStandard(_, p) => p.some
    }

  trait PathWatcher[F[_]] {

    /** path being watched */
    def path: Fs2Path

    /** stream of notifications */
    def changes: fs2.Stream[F, Unit]
  }

  object PathWatcher {
    def apply[F[_]: Temporal: Files](
        p: Fs2Path,
        watchEvery: FiniteDuration
    ): Resource[F, PathWatcher[F]] =
      for {

        currentTimeModified <- Files[F].getLastModifiedTime(p).toResource

        lastTimeModified <- fs2.concurrent
          .SignallingRef[F]
          .of(currentTimeModified)
          .toResource

        _ <- fs2.Stream
          .fixedDelay[F](watchEvery)
          .evalMap { _ =>
            Files[F].getLastModifiedTime(p).flatMap { time =>
              lastTimeModified.set(time)
            }
          }
          .compile
          .drain
          .background

      } yield new PathWatcher[F] {

        override def path: Fs2Path = p

        override def changes: fs2.Stream[F, Unit] =
          lastTimeModified.changes.discrete.as(())
      }

  }

  def runServerImpl[F[_]: Files: Network](
      cli: Cli
  )(using F: Async[F], C: Console[F]): F[Unit] =
    (for {
      killSwitch <- F.deferred[Unit].toResource
      flipKillSwitch = killSwitch.complete(()).void

      cwd <- Files[F].currentWorkingDirectory.toResource

      eventQ <- Queue.unbounded[F, Unit].toResource

      wsOut <- Queue.unbounded[F, String].toResource

      _ <- cli.watch
        .getOrElse(cwd :: Nil)
        .parTraverse(p =>
          PathWatcher[F](p, 1.second).use { pw =>
            pw.changes.evalMap(eventQ.offer).compile.drain
          }
        )
        .onError(t => C.errorln("file watcher failed: $t") *> flipKillSwitch)
        .background

      _ <- fs2.Stream
        .fromQueueUnterminated(eventQ)
        // .map(pathFromEvent)
        // .filterNot(_.fold(false)(cli.ignorePath))
        .debounce(cli.wait0)
        .evalMap(pMaybe =>
          wsOut.offer("reload") *> C.println(
            s"""${AnsiColor.CYAN}Changes detected${AnsiColor.RESET}"""
          )
        )
        .compile
        .drain
        .background

      indexHtmlWithInjectedScriptTag <- (for {
        indexHtml <- Files[F].readUtf8(cli.entryFile).compile.onlyOrError

        scriptTagToInject <- fs2.io
          .readClassLoaderResource[F]("injected.html")
          .through(fs2.text.utf8Decode)
          .compile
          .onlyOrError

        result <- F.fromOption(
          ScriptInjector(indexHtml, scriptTagToInject),
          new RuntimeException("Failed to inject script tag")
        )
      } yield result).toResource

      app <- Resource
        .pure(new StaticFileServer[F](indexHtmlWithInjectedScriptTag).routes)
        .flatMap(app0 =>
          cli.proxy.fold(Resource.pure(app0)) { case (path, url) =>
            EmberClientBuilder
              .default[F]
              .build
              .map(client => ProxyMiddleware(path, url, client)(app0))
          }
        )
        .map(ErrorLoggingMiddleware[F])
        .map(cli.cors.applyIf(_)(middleware.CORS.policy.withAllowOriginAll(_)))
        .map(
          cli.verbose.applyIf(_)(
            middleware.Logger.httpRoutes[F](
              logHeaders = true,
              logBody = true,
              logAction = ((msg: String) => C.println(msg)).some
            )(_)
          )
        )

      server <- EmberServerBuilder
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

    } yield killSwitch.get).useEval

  def runServer[F[_]: Files: Network](
      cli: Cli
  )(using F: Async[F], C: Console[F]): F[Unit] =
    runServerImpl(cli).recoverWith { case ex: java.net.BindException =>
      for {
        _ <- C.errorln(ex.toString)
        _ <- C.println(
          s"""${AnsiColor.RED}Failed to start server.
          Perhaps port ${cli.port} is already in use.
          Attempt to find other port ..${AnsiColor.RESET}"""
        )
        rg <- Random.scalaUtilRandom[F]
        newPort <- rg
          .betweenInt(1024, 65535)
          .map(Port.fromInt(_).get) // `get` is safe here
        _ <- runServer(cli.withPort(newPort))
      } yield ()
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
      .option[String](
        "entry-file",
        "Index html to be served as entry file - defaults to `index.html`"
      )
      .withDefault("index.html")
      .map(ef => Fs2Path(ef))

    val ignore = Opts
      .options[String]("ignore", "List of path regex to not watch")
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
      ).mapN(Cli.apply)

    cli.map(runServer[IO](_).as(ExitCode.Success))

  }
}
