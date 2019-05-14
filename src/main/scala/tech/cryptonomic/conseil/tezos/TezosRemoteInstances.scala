package tech.cryptonomic.conseil.tezos

import tech.cryptonomic.conseil.config.{HttpStreamingConfiguration, NetworkCallsConfiguration}
import tech.cryptonomic.conseil.config.Platforms.TezosConfiguration
import tech.cryptonomic.conseil.generic.chain.RpcHandler
import tech.cryptonomic.conseil.util.JsonUtil.JsonString
import cats.data.Kleisli

/** Provides RPC instances for the tezos chain */
object TezosRemoteInstances {

  object Cats {
    import cats.effect.IO

    object IOEff extends IOEff

    trait IOEff {
      import tech.cryptonomic.conseil.tezos.TezosRemoteInstances.Akka.RemoteContext
      import tech.cryptonomic.conseil.tezos.TezosRemoteInstances.Akka.Futures._
      import tech.cryptonomic.conseil.util.EffectsUtil._

      //creates an IO instance on top of the one for Futures
      implicit def ioRpcHandlerInstance(implicit context: RemoteContext): RpcHandler.Aux[IO, String, JsonString, String] =
        new RpcHandler[IO] {
          val futureRpc = futureRpcHandlerInstance(context)

          //a partial URL path
          type Command = String
          // the json response content
          type Response = String
          // payload is formally verified json
          type PostPayload = JsonString

          override def getQuery = Kleisli {
            in => toIO(futureRpc.getQuery.run(in))
          }

          override def postQuery = Kleisli {
            case in => toIO(futureRpc.postQuery.run(in))
          }
        }

    }

  }

  /** Instances based on Akka toolkit*/
  object Akka {
    import com.typesafe.scalalogging.Logger
    import cats.Monoid
    import scala.concurrent.Future
    import akka.actor.ActorSystem
    import akka.stream.ActorMaterializer
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model._

    /** Describes configurations for the tezos node, needed to actually execute the calls */
    case class RemoteContext(
      tezosConfig: TezosConfiguration,
      requestConfig: NetworkCallsConfiguration,
      streamingConfig: HttpStreamingConfiguration
    )(implicit val system: ActorSystem) {

      object ShutdownComplete extends ShutdownComplete
      private val rejectingCalls = new java.util.concurrent.atomic.AtomicBoolean(false)

      def translateCommandToUrl(command: String): String = {
        import tezosConfig.nodeConfig._
        s"$protocol://$hostname:$port/${pathPrefix}chains/main/$command"
      }

      def shutdown(): Future[ShutdownComplete] = {
        rejectingCalls.compareAndSet(false, true)
        Http(system).shutdownAllConnectionPools().map(_ => this.ShutdownComplete)(system.dispatcher)
      }

      /** Allows to stop calling the service when a shutdown is initiated, short-circuiting the response */
      def withRejectionControl[T, Container[_]](call: => Container[T])(implicit mono: Monoid[Container[T]]): Container[T] =
        if (rejectingCalls.get) mono.empty else call

    }

    /** Statically provides rpc intances based on Akka and scala Future*/
    object Futures extends Futures

    /** Mix-in this to get instances for async rpc calls on top of scala Future*/
    trait Futures {
      import cats.instances.future._
      import cats.syntax.apply._
      import scala.concurrent.ExecutionContext
      import scala.util.control.NoStackTrace
      import akka.http.scaladsl.settings.ConnectionPoolSettings

      //might be actually unlawful, but we won't use it for append, only for empty
      private implicit def futureMonoid[T](implicit ec: ExecutionContext) = new Monoid[Future[T]] {
        override def combine(x: Future[T], y: Future[T]) = x *> y
        override def empty: Future[T] = Future.failed(new IllegalStateException("Tezos node requests will no longer be accepted.") with NoStackTrace)
      }


      /** The actual instance
       * @param context we need to convert to the fetcher, based on an implicit `RemoteContext`
       */
      implicit def futureRpcHandlerInstance(implicit context: RemoteContext) =
        new RpcHandler[Future] {
          import cats.data.Kleisli
          import context._

          private val logger = Logger("Akka.Futures.RpcHandler")

          implicit val system = context.system
          implicit val dispatcher = system.dispatcher
          implicit val materializer = ActorMaterializer()

          /* Connection pool settings customization */
          val requestsConnectionPooling: ConnectionPoolSettings =
            ConnectionPoolSettings(context.streamingConfig.pool)

          //a partial URL path
          type Command = String
          // the json response content
          type Response = String
          // payload is formally verified json
          type PostPayload = JsonString

          override def getQuery: Kleisli[Future, Command, Response] = Kleisli {
            command => withRejectionControl {
              val url = translateCommandToUrl(command)
              val httpRequest = HttpRequest(HttpMethods.GET, url)
              logger.debug("Async querying URL {} for platform Tezos and network {}", url, tezosConfig.network)

              for {
                response <- Http(system).singleRequest(httpRequest, settings = requestsConnectionPooling)
                strict <- response.entity.toStrict(requestConfig.GETResponseEntityTimeout)
              } yield {
                val content = strict.data.utf8String
                logger.debug("GET query result: {}", content)
                JsonString sanitize content
              }
            }
          }

          override def postQuery: Kleisli[Future, (Command, Option[PostPayload]), Response] = Kleisli {
            case (command, payload) => withRejectionControl {
              val url = translateCommandToUrl(command)
              logger.debug("Async querying URL {} for platform Tezos and network {} with payload {}", url, tezosConfig.network, payload)
              val postedData = payload.getOrElse(JsonString.emptyObject)
              val httpRequest = HttpRequest(
                HttpMethods.POST,
                url,
                entity = HttpEntity(ContentTypes.`application/json`, postedData.json.getBytes())
              )

              for {
                response <- Http(system).singleRequest(httpRequest)
                strict <- response.entity.toStrict(requestConfig.POSTResponseEntityTimeout)
              } yield {
                val content = strict.data.utf8String
                logger.debug("POST query result: {}", content)
                JsonString sanitize content
              }

            }
          }

        }

    }

  }

}