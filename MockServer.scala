import cats.effect.implicits.*
import cats.effect.IOApp.Simple
import cats.effect.IO
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.implicits.* // <+>
import org.http4s.server.Server
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.dsl.Http4sDsl
import org.http4s.HttpRoutes
import org.http4s.server.Router
import fs2.io.net.Network

object MockServer extends Simple {

  final class MockEndpoints[F[_]: Async] extends Http4sDsl[F] {

    private val prefix = "/api"

    private val alive = HttpRoutes.of[F] { case GET -> Root / "alive" =>
      Ok("I am alive")
    }

    private val version = HttpRoutes.of[F] { case GET -> Root / "version" =>
      Ok("0.0.1")
    }

    private val description = HttpRoutes.of[F] {
      case GET -> Root / "description" =>
        Ok("A mock server")
    }

    val routes = Router(prefix -> (alive <+> version <+> description))

  }

  def buildServer[F[_]: Network](implicit F: Async[F]): Resource[F, Server] =
    for {
      host <- F
        .fromOption(
          Host.fromString("localhost"),
          new IllegalArgumentException("Bad host")
        )
        .toResource

      port <- F
        .fromOption(
          Port.fromInt(8090),
          new IllegalArgumentException("Bad port")
        )
        .toResource

      server <- EmberServerBuilder
        .default[F]
        .withHost(host)
        .withPort(port)
        .withHttpApp(new MockEndpoints().routes.orNotFound)
        .build

    } yield server

  override def run: IO[Unit] = buildServer[IO].use(_ => IO.never)
}
