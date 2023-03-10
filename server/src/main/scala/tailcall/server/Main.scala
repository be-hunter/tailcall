package tailcall.server

import caliban.GraphQLRequest
import tailcall.runtime.ast.Blueprint
import tailcall.runtime.http.HttpClient
import tailcall.runtime.service._
import tailcall.server.service.BinaryDigest.{Algorithm, Digest}
import tailcall.server.service.{BinaryDigest, SchemaRegistry}
import zio._
import zio.http._
import zio.http.model.{HttpError, Method}
import zio.json.{DecoderOps, EncoderOps}

object Main extends ZIOAppDefault {
  val adminREST = Http.collectZIO[Request] {
    case req @ Method.PUT -> !! / "schemas" => for {
        body      <- req.body.asCharSeq
        blueprint <- Blueprint.decode(body) match {
          case Left(value)  => ZIO.fail(HttpError.BadRequest(value))
          case Right(value) => ZIO.succeed(value)
        }
        digest    <- SchemaRegistry.add(blueprint)
      } yield Response.json(digest.toJson)

    case Method.GET -> !! / "schemas" => for {
        list <- SchemaRegistry.list(0, Int.MaxValue)
      } yield Response.json(list.toJson)

    case Method.DELETE -> !! / "schemas" / alg / digest => for {
        algorithm <- ZIO.fromOption(Algorithm.fromString(alg))
          .orElseFail(HttpError.BadRequest(s"Invalid algorithm ${alg}"))
        found     <- SchemaRegistry.drop(Digest.fromHex(algorithm, digest))
        _         <- ZIO.fail(HttpError.NotFound(s"Schema ${digest} not found")).when(found)
      } yield Response.ok

    case Method.GET -> !! / "schemas" / alg / digest => for {
        algorithm <- ZIO.fromOption(Algorithm.fromString(alg))
          .orElseFail(HttpError.BadRequest(s"Invalid algorithm ${alg}"))
        schema    <- SchemaRegistry.get(Digest.fromHex(algorithm, digest))
        blueprint <- schema match {
          case Some(blueprint) => ZIO.succeed(blueprint)
          case None            => ZIO.fail(HttpError.NotFound(s"Schema ${digest} not found"))
        }
      } yield Response.json(blueprint.toJson)

    case Method.GET -> !! / "health" => ZIO.succeed(Response.ok)
  }

  private def decodeQuery(body: Body): ZIO[Any, Throwable, String] =
    for {
      text  <- body.asCharSeq
      req   <- text.fromJson[GraphQLRequest] match {
        case Left(value)  => ZIO.fail(HttpError.BadRequest(value))
        case Right(value) => ZIO.succeed(value)
      }
      query <- req.query match {
        case Some(value) => ZIO.succeed(value)
        case None        => ZIO.fail(HttpError.BadRequest("Query is required"))
      }
    } yield query

  private def userGraphQL =
    Http.collectZIO[Request] { case req @ Method.POST -> !! / "graphql" / alg / id =>
      pprint.pprintln(alg)
      for {
        alg         <- Algorithm.fromString(alg) match {
          case Some(value) => ZIO.succeed(value)
          case None        => ZIO.fail(HttpError.BadRequest("Invalid algorithm"))
        }
        digest = Digest.fromHex(alg, id)
        schema      <- SchemaRegistry.get(digest)
        result      <- schema match {
          case Some(value) => value.toGraphQL
          case None        => ZIO.fail(HttpError.NotFound(s"Schema ${id} not found"))
        }
        query       <- decodeQuery(req.body)
        interpreter <- result.interpreter
        res         <- interpreter.execute(query)
      } yield Response.json(res.toJson)
    }

  private val adminGraphQL = Http.collectZIO[Request] { case req =>
    for {
      query       <- decodeQuery(req.body)
      interpreter <- AdminGraphQL.graphQL.interpreter
      res         <- interpreter.execute(query)
    } yield Response.json(res.toJson)
  }

  val graphQL = Http.collectRoute[Request] {
    case Method.GET -> !! / "graphql"          => Http.fromResource("graphiql.html")
    case Method.POST -> !! / "graphql"         => adminGraphQL
    case Method.POST -> !! / "graphql" / _ / _ => userGraphQL
  }

  def sanitized[R](http: HttpApp[R, Throwable]): App[R] =
    http.tapErrorZIO(err => ZIO.succeed(pprint.pprintln(err))).mapError {
      case error: HttpError => Response.fromHttpError(error)
      case error            => Response.fromHttpError(HttpError.InternalServerError(cause = Option(error)))
    }

  val userServer: ZIO[Any, Throwable, Nothing] = Server.serve(sanitized(graphQL ++ adminREST)).provide(
    ServerConfig.live.map(_.update(_.port(8080))),
    SchemaRegistry.persistent(this.getClass.getResource("/").getPath),
    GraphQLGenerator.live,
    TypeGenerator.live,
    StepGenerator.live,
    EvaluationRuntime.live,
    HttpClient.live,
    Client.default,
    BinaryDigest.sha256,
    Server.live
  )

  override val run = userServer.exitCode
}
