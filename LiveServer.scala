import cats.MonadThrow
import cats.data.Kleisli
import cats.data.NonEmptyList
import cats.data.OptionT
import cats.effect.*
import cats.effect.implicits.*
import cats.effect.std.Console
import cats.effect.std.Queue
import cats.effect.std.Random
import cats.effect.std.Supervisor
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.monovore.decline.*
import com.monovore.decline.effect.*
import fs2.concurrent.SignallingRef
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
      cwd: Option[Fs2Path],
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

  object ScriptInjector {
    def apply(html: String, script: String): Option[String] =
      html.indexOf("</html>") match {
        case -1 => None
        case ix => html.patch(ix, script, 0).some
      }
  }

  final class StaticFileServer[F[_]: Files](
      indexHtml: String,
      cwd: Fs2Path
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
          .fromPath[F](cwd / Fs2Path(s".${path.toString}"))
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

  trait PathWatcher[F[_]] {

    /** stream of notifications */
    def changes: fs2.Stream[F, Unit]
  }

  object PathWatcher {
    def apply[F[_]: Temporal: Files: Console](
        p: Fs2Path,
        watchEvery: FiniteDuration
    )(implicit C: Console[F]): Resource[F, PathWatcher[F]] =
      for {
        currentLmt <- Files[F].getLastModifiedTime(p).toResource

        lmt <- SignallingRef
          .of[F, Option[FiniteDuration]](currentLmt.some)
          .toResource

        _ <- fs2.Stream
          .fixedDelay(watchEvery)
          .evalMap(_ =>
            Temporal[F].ifM(Files[F].exists(p))(
              Files[F]
                .getLastModifiedTime(p)
                .flatMap(dur => lmt.set(dur.some)),
              lmt.set(None)
            )
          )
          .interruptWhen(lmt.map(_.isEmpty))
          .compile
          .drain
          .background

      } yield new PathWatcher[F] {

        override def changes: fs2.Stream[F, Unit] =
          lmt.changes.discrete.as(())
      }
  }

  def watchPathImpl[F[_]: Concurrent: Temporal: Files: Console](
      p: Fs2Path,
      watchEvery: FiniteDuration,
      q: Queue[F, Fs2Path]
  ): F[Unit] =
    fs2.Stream
      .resource(PathWatcher(p, watchEvery))
      .flatMap(_.changes)
      .as(p)
      .evalMap(q.offer)
      .compile
      .drain

  def runServerImpl[F[_]: Files: Network](
      cli: Cli
  )(using F: Async[F], C: Console[F]): F[Unit] =
    (for {
      killSwitch <- F.deferred[Unit].toResource
      flipKillSwitch = killSwitch.complete(()).void

      cwd <- cli.cwd
        .fold(Files[F].currentWorkingDirectory)(_.pure[F])
        .toResource

      eventQ <- Queue.unbounded[F, Fs2Path].toResource

      wsOut <- Queue.unbounded[F, String].toResource

      _ <- fs2.Stream
        .emits(cli.watch.getOrElse(cwd :: Nil))
        .covary[F]
        .flatMap(Files[F].walk(_))
        .parEvalMapUnorderedUnbounded(watchPathImpl(_, 1.second, eventQ))
        .compile
        .drain
        .onError(t => C.errorln("file watcher failed: $t") *> flipKillSwitch)
        .background

      _ <- fs2.Stream
        .fromQueueUnterminated(eventQ)
        .filterNot(cli.ignorePath)
        .debounce(cli.wait0)
        .evalMap(p =>
          wsOut.offer("reload") *> C.println(
            s"""${AnsiColor.CYAN}Changes detected in `$p`${AnsiColor.RESET}"""
          )
        )
        .compile
        .drain
        .background

      indexHtmlWithInjectedScriptTag <- Files[F]
        .readUtf8(cwd / cli.entryFile)
        .compile
        .onlyOrError
        .flatMap { indexHtml =>
          F.fromOption(
            ScriptInjector(indexHtml, scriptTagToInject),
            new RuntimeException("Failed to inject script tag")
          )
        }
        .toResource

      app <- Resource
        .pure(
          new StaticFileServer[F](indexHtmlWithInjectedScriptTag, cwd).routes
        )
        .flatMap(app0 =>
          cli.proxy.fold(Resource.pure(app0)) { case (path, url) =>
            EmberClientBuilder
              .default[F]
              .build
              .map(client => ProxyMiddleware(path, url, client)(app0))
          }
        )
        .map(ErrorLoggingMiddleware[F])
        .map(
          middleware.RequestLogger.httpRoutes[F](
            logHeaders = false,
            logBody = false,
            logAction = (
                (msg: String) =>
                  msg.split(" ").toList match {
                    case List(http, method, path) =>
                      C.println(
                        s"${AnsiColor.YELLOW}$http${AnsiColor.RESET} ${AnsiColor.BLUE}$method${AnsiColor.RESET} $path"
                      )
                    case other => C.println(other)
                  }
            ).some
          )
        )
        .map(cli.cors.applyIf(_)(middleware.CORS.policy.withAllowOriginAll(_)))
        .map(
          cli.verbose.applyIf(_)(
            middleware.Logger.httpRoutes[F](
              logHeaders = true,
              logBody = true,
              logAction = (C.println).some
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
          {
            val segment = cli.proxy.map(_._1.toString).getOrElse("")
            val uri = cli.proxy.map(_._2.toString).getOrElse("")
            C.println(
              s"""|${AnsiColor.MAGENTA}Live server of $cwd started at: 
                  |http://${cli.host}:${cli.port}
                  |Remapping /$segment to $uri/$segment ${AnsiColor.RESET}""".stripMargin
            ) *> C.println(
              s"""${AnsiColor.RED}Ready to watch changes${AnsiColor.RESET}"""
            )
          }
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
      .mapValidated(Host.fromString(_).toValidNel("Invalid host"))

    val port = Opts
      .option[Int]("port", "Port")
      .withDefault(8080)
      .mapValidated(Port.fromInt(_).toValidNel("Invalid port"))

    val wait0 =
      Opts
        .option[Int]("wait", "Wait before reload (ms)")
        .withDefault(1000)
        .map(_.milliseconds)

    val cwd =
      Opts
        .option[String]("cwd", "Current working directory")
        .map(Fs2Path(_))
        .orNone

    val entryFile = Opts
      .option[String](
        "entry-file",
        "Index html to be served as entry file - defaults to `index.html`"
      )
      .withDefault("index.html")
      .map(Fs2Path(_))

    val ignore = Opts
      .options[String]("ignore", "List of path regex to not watch")
      .orNone

    val watch = Opts
      .options[String]("watch", "List of paths to watch")
      .map(_.toList.map(Fs2Path(_)))
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
        cwd,
        entryFile,
        ignore,
        watch,
        proxy,
        cors,
        verbose
      ).mapN(Cli.apply)

    cli.map(runServer[IO](_).as(ExitCode.Success))

  }

  private lazy val scriptTagToInject = """
  <script type="text/javascript">
  // Code injected by fs2-live-server
  // <![CDATA[  <-- For SVG support
    if ('WebSocket' in window) {
	    (function() {
		    function refreshCSS() {
			    var sheets = [].slice.call(document.getElementsByTagName("link"));
			    var head = document.getElementsByTagName("head")[0];
			    for (var i = 0; i < sheets.length; ++i) {
				      var elem = sheets[i];
				      head.removeChild(elem);
				      var rel = elem.rel;
				      if (elem.href && typeof rel != "string" || rel.length == 0 || rel.toLowerCase() == "stylesheet") {
					      var url = elem.href.replace(/(&|\?)_cacheOverride=\d+/, '');
					      elem.href = url + (url.indexOf('?') >= 0 ? '&' : '?') + '_cacheOverride=' + (new Date().valueOf());
      				}
		      		head.appendChild(elem);
			    }
		    }
		  var protocol = window.location.protocol === 'http:' ? 'ws://' : 'wss://';
		  var address = protocol + window.location.host + window.location.pathname + 'ws';
		  var socket = new WebSocket(address);
		  socket.onmessage = function(msg) {
			  if (msg.data == 'reload') window.location.reload();
			  else if (msg.data == 'refreshcss') refreshCSS();
		  };
		  console.log('Live reload enabled.');
	  })();
  }
  </script>"""

}
