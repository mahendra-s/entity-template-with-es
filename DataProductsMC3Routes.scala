package ai.taiyo.mc3

import ai.taiyo.User
import ai.taiyo.UserAPIAuthService.myUserPassAuthenticator
import ai.taiyo.mc3.EntityRegistry._
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{pathPrefix, _}
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.http.scaladsl.unmarshalling.PredefinedFromStringUnmarshallers
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import org.json4s.DefaultFormats
import org.json4s.native.JsonMethods.parse
import org.json4s.native.Serialization.{write, writePretty}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class DataProductsMC3Routes
(entityRegistry: ActorRef[EntityRegistry.Command])
(implicit val system: ActorSystem[_]) extends LazyLogging with PredefinedFromStringUnmarshallers {

  implicit val formats = DefaultFormats

  implicit def f1(jsonString: String): Either[Throwable, DataProductEntity2] = Try(parse(jsonString).extract[DataProductEntity2]).toEither

  implicit def f2(jsonString: String): Either[Throwable, List[DataProductEntity2]] = Try(parse(jsonString).extract[List[DataProductEntity2]]).toEither

  private implicit val timeout = Timeout.create(system.settings.config.getDuration("my-app.routes.ask-timeout"))

  def getEntities[T](limit: Int, onlyLatest: Boolean): Future[GetEntitiesResponse[T]] = futureFailHandle {
    entityRegistry.ask(GetEntities[T](limit, onlyLatest, _))
  }

  def findEntities[T](params: Map[String, String], onlyLatest: Boolean, limit: Int = 10): Future[GetEntitiesResponse[T]] = futureFailHandle {
    entityRegistry.ask(FindEntities[T](params, onlyLatest, limit, _))
  }

  def multiMatchFindEntities[T](query: String, fields: List[String], `type`: String = "best_fields"
                                , sourceInclude: List[String] = Nil, sourceExclude: List[String] = Nil
                                , limit: Option[Int] = None
                                , params: Map[String, String] = Map.empty): Future[GetEntitiesResponse[T]] = futureFailHandle {
    entityRegistry.ask(MultiMatchFindEntities[T](query, fields, `type`, sourceInclude, sourceExclude, limit, params, _))
  }

  def getEntity[T](id: String): Future[GetEntityResponse[T]] = futureFailHandle {
    entityRegistry.ask(GetEntity[T](id, _))
  }

  def createEntity[T](entity: T): Future[ActionPerformed] = futureFailHandle {
    entityRegistry.ask(CreateEntity[T](entity, _))
  }

  def createBulkEntity[T](entities: List[T]): Future[ActionPerformed] = futureFailHandle {
    entityRegistry.ask(CreateEntities[T](entities, _))
  }

  def updateEntity[T](entity: T): Future[ActionPerformed] = futureFailHandle {
    entityRegistry.ask(UpdateEntity[T](entity, _))
  }

  def deleteEntity(id: String): Future[ActionPerformed] = futureFailHandle {
    entityRegistry.ask(DeleteEntity(id, _))
  }

  def futureFailHandle[T](result: Future[Try[T]]): Future[T] = {
    result.map(Future.fromTry(_))(system.executionContext).flatten
  }

  implicit def myExceptionHandler: ExceptionHandler = {
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.server._
    import Directives._

    ExceptionHandler {
      case th: Throwable =>
        extractUri { uri =>
          logger.error(s"Request to $uri could not be handled normally")
          complete(StatusCodes.OK, s"ERROR: ${th.getMessage}")
        }
      case _ =>
        extractUri { uri =>
          logger.error(s"Request to $uri could not be handled normally")
          complete(StatusCodes.OK, s"Please check taiyo-serve logs")
        }
    }
  }

  def routes(baseName: String = "data-product-config"): Route = handleExceptions(myExceptionHandler) {
    pathPrefix(baseName) {
      def find(env_prefix: String): Route = pathPrefix(env_prefix / "find") {
        get {
          parameters("limit".as[Int].withDefault(10)
            , "pretty".as[Boolean].withDefault(false)
            , "onlyLatest".as[Boolean].withDefault(false)
          ) { (limit: Int, pretty: Boolean, onlyLatest: Boolean) =>
            parameterMap { params: Map[String, String] =>
              onComplete(findEntities[DataProductEntity2](params.filterNot { e => List("pretty", "onlyLatest", "limit").contains(e._1) } + ("deploymentStatus.keyword" -> env_prefix), onlyLatest, limit)) {
                case Success(result) =>
                  complete(StatusCodes.OK, if (params.getOrElse("pretty", "false").compareToIgnoreCase("false") == 0)
                    writePretty(result.entities) else write(result.entities))
                case Failure(throwable: Throwable) =>
                  logger.error(throwable.getMessage, throwable)
                  complete(StatusCodes.OK, s"ERROR: ${throwable.getMessage}")
              }
            }
          }
        }
      }

      Route.seal {
        authenticateBasic(realm = "secure site", myUserPassAuthenticator) { user: User =>
          //        authorize(hasAdminPermissions(user)) {
          authorize(true) {
            concat(
              pathEnd {
                concat(
                  get {
                    parameters("limit".as[Int].withDefault(10)
                      , "pretty".as[Boolean].withDefault(false)
                      , "onlyLatest".as[Boolean].withDefault(false)
                    ) { (limit: Int, pretty: Boolean, onlyLatest: Boolean) =>
                      onComplete(getEntities[DataProductEntity2](limit, onlyLatest)) {
                        case Success(result) =>
                          complete(StatusCodes.OK, if (pretty) writePretty(result.entities) else write(result.entities))
                        case Failure(exception: Throwable) => logger.error(exception.getMessage, exception)
                          complete(StatusCodes.OK, s"ERROR: ${exception.getMessage}")
                      }
                    }
                  }
                  , post {
                    entity(as[String]) { entity: String =>
                      f1(entity) match {
                        case Left(value: Throwable) => logger.error(value.getMessage, value)
                          complete(StatusCodes.OK, value.getMessage)
                        case Right(entity: DataProductEntity2) =>
                          entity.validateFields
                          onComplete(createEntity[DataProductEntity2](entity.copy(id = Some(entity.shortName)))) {
                            case Success(performed) =>
                              complete(StatusCodes.Created, performed.description)
                            case Failure(throwable: Throwable) =>
                              logger.error(throwable.getMessage, throwable)
                              complete(StatusCodes.OK, s"ERROR: ${throwable.getMessage}")
                          }
                      }
                    }
                  }
                )
              },
              pathPrefix("find") {
                get {
                  parameters("limit".as[Int].withDefault(10)
                    , "pretty".as[Boolean].withDefault(false)
                    , "onlyLatest".as[Boolean].withDefault(false)
                  ) { (limit: Int, pretty: Boolean, onlyLatest: Boolean) =>
                    parameterMap { params: Map[String, String] =>
                      onComplete(findEntities[DataProductEntity2](params.filterNot { e => List("pretty", "onlyLatest", "limit").contains(e._1) }, onlyLatest, limit)) {
                        case Success(result) =>
                          complete(StatusCodes.OK, if (params.getOrElse("pretty", "false").compareToIgnoreCase("false") == 0)
                            writePretty(result.entities) else write(result.entities))
                        case Failure(throwable: Throwable) =>
                          logger.error(throwable.getMessage, throwable)
                          complete(StatusCodes.OK, s"ERROR: ${throwable.getMessage}")
                      }
                    }
                  }
                }
              },
              pathPrefix("multiMatchFind") {
                get {
                  parameters(
                    "query".as[String]
                    , "fields".as(CsvSeq[String])
                    , "type".as[String].withDefault("best_fields")
                    , "sourceInclude".as(CsvSeq[String]).withDefault(Nil)
                    , "sourceExclude".as(CsvSeq[String]).withDefault(Nil)
                    , "limit".as[Int].optional
                    , "pretty".as[Boolean].withDefault(false)
                  ) { (query: String
                       , fields: Seq[String]
                       , qType: String
                       , sourceInclude: Seq[String]
                       , sourceExclude: Seq[String]
                       , limit: Option[Int]
                       , pretty: Boolean) =>
                    entity(as[String]) { entity: String =>
                      val params: Map[String, String] =
                        Try(parse(entity)
                          .extract[Map[String, String]])
                          .getOrElse(Map.empty)
                      onComplete(multiMatchFindEntities[DataProductEntity2](
                        query
                        , fields.toList
                        , qType
                        , sourceInclude.toList
                        , sourceExclude.toList
                        , limit
                        , params
                      )) {
                        case Success(result) =>
                          complete(StatusCodes.OK, if (pretty) writePretty(result.entities) else write(result.entities))
                        case Failure(throwable: Throwable) =>
                          logger.error(throwable.getMessage, throwable)
                          complete(StatusCodes.OK, s"ERROR: ${throwable.getMessage}")
                      }
                    }
                  }
                }
              },
              pathPrefix("bulk") {
                post {
                  entity(as[String]) { entity: String =>
                    f2(entity) match {
                      case Left(value: Throwable) =>
                        logger.error(value.getMessage, value)
                        complete(StatusCodes.OK, value.getMessage)
                      case Right(entities: Seq[DataProductEntity2]) =>
                        onComplete(createBulkEntity[DataProductEntity2](entities)) {
                          case Success(performed: ActionPerformed) =>
                            complete(StatusCodes.OK, performed.description)
                          case Failure(throwable: Throwable) =>
                            logger.error(throwable.getMessage, throwable)
                            complete(StatusCodes.OK, s"ERROR: ${throwable.getMessage}")
                        }
                    }
                  }
                }
              },
              find("dev-ift"),
              find("dev2-ift"),
              pathPrefix("add_dp_link") {
                path(Segment) { entityId =>
                  get {
                    parameters("dp_id".as[String]) { (linkDPId: String) =>
                      rejectEmptyResponse {
                        onComplete(
                          getEntity[DataProductEntity2](entityId).map { response: GetEntityResponse[DataProductEntity2] =>
                            getEntity[DataProductEntity2](linkDPId).map { tobeAdded: GetEntityResponse[DataProductEntity2] =>
                              val dp = response.maybeUser.get
                              val updatedDP: DataProductEntity2 = dp.copy(
                                linkedDP = dp.linkedDP ::: List(linkDPId),
                                indices = dp.indices ::: tobeAdded.maybeUser.get.indices
                              )
                              updateEntity[DataProductEntity2](updatedDP)
                            }(system.executionContext).flatten
                          }(system.executionContext).flatten
                        ) {
                          case Success(performed: ActionPerformed) =>
                            complete(StatusCodes.OK, performed.description)
                          case Failure(throwable: Throwable) =>
                            logger.error(throwable.getMessage, throwable)
                            complete(StatusCodes.OK, s"ERROR: ${throwable.getMessage}")
                        }
                      }
                    }
                  }
                }
              }
              , pathPrefix("remove_dp_link") {
                path(Segment) { entityId =>
                  get {
                    parameters("dp_id".as[String]) { (linkDPId: String) =>
                      rejectEmptyResponse {
                        onComplete(
                          getEntity[DataProductEntity2](entityId).map { response: GetEntityResponse[DataProductEntity2] =>
                            getEntity[DataProductEntity2](linkDPId).map { tobeAdded: GetEntityResponse[DataProductEntity2] =>
                              val dp = response.maybeUser.get
                              val updatedDP: DataProductEntity2 = dp.copy(
                                linkedDP = dp.linkedDP.filterNot(_ == linkDPId),
                                indices = dp.indices.filterNot(tobeAdded.maybeUser.get.indices.contains)
                              )
                              updateEntity[DataProductEntity2](updatedDP)
                            }(system.executionContext).flatten
                          }(system.executionContext).flatten
                        ) {
                          case Success(performed: ActionPerformed) =>
                            complete(StatusCodes.OK, performed.description)
                          case Failure(throwable: Throwable) =>
                            logger.error(throwable.getMessage, throwable)
                            complete(StatusCodes.OK, s"ERROR: ${throwable.getMessage}")
                        }
                      }
                    }
                  }
                }
              },
              path(Segment) { entityId =>
                concat(
                  get {
                    parameters("pretty".as[Boolean].withDefault(false)) { pretty: Boolean =>
                      rejectEmptyResponse {
                        onSuccess(getEntity[DataProductEntity2](entityId)) { response =>
                          complete(StatusCodes.OK, if (pretty) writePretty(response.maybeUser) else write(response.maybeUser))
                        }
                      }
                    }
                  }
                  , delete {
                    onSuccess(deleteEntity(entityId)) { performed =>
                      complete(StatusCodes.OK, performed.description)
                    }
                  }
                  , put {
                    entity(as[String]) { entity: String =>
                      f1(entity) match {
                        case Left(throwable: Throwable) =>
                          logger.error(throwable.getMessage, throwable)
                          complete(StatusCodes.OK, throwable.getMessage)
                        case Right(entity: DataProductEntity2) =>
                          onComplete(updateEntity[DataProductEntity2](entity)) {
                            case Success(performed: ActionPerformed) =>
                              complete(StatusCodes.OK, performed.description)
                            case Failure(throwable: Throwable) =>
                              logger.error(throwable.getMessage, throwable)
                              complete(StatusCodes.OK, s"ERROR: ${throwable.getMessage}")
                          }
                      }
                    }
                  }
                )
              }
            )
          }
        }
      }
    }

  }
}
