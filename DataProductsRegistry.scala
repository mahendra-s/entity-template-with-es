package ai.taiyo.mc3


import ai.taiyo.dataproducts.DataProductService
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}

import scala.util.Try


object EntityRegistry {

  sealed trait Command

  final case class GetEntities[T](limit: Int, onlyLatest: Boolean, replyTo: ActorRef[Try[GetEntitiesResponse[T]]]) extends Command

  final case class FindEntities[T](params: Map[String, String], onlyLatest: Boolean, limit: Int = 10, replyTo: ActorRef[Try[GetEntitiesResponse[T]]]) extends Command

  final case class MultiMatchFindEntities[T](query: String, fields: List[String], `type`: String = "best_fields"
                                             , sourceInclude: List[String] = Nil, sourceExclude: List[String] = Nil
                                             , limit: Option[Int] = None
                                             , params: Map[String, String]
                                             , replyTo: ActorRef[Try[GetEntitiesResponse[T]]]) extends Command

  final case class CreateEntity[T](entity: T, replyTo: ActorRef[Try[ActionPerformed]]) extends Command

  final case class CreateEntities[T](entity: List[T], replyTo: ActorRef[Try[ActionPerformed]]) extends Command

  final case class UpdateEntity[T](entity: T, replyTo: ActorRef[Try[ActionPerformed]]) extends Command

  final case class GetEntity[T](name: String, replyTo: ActorRef[Try[GetEntityResponse[T]]]) extends Command

  final case class DeleteEntity(name: String, replyTo: ActorRef[Try[ActionPerformed]]) extends Command

  final case class GetEntityResponse[T](maybeUser: Option[T])

  final case class GetEntitiesResponse[T](entities: List[T])

  final case class ActionPerformed(description: String)

  def apply(): Behavior[Command] = registry(Set.empty)

  private def registry(entities: Set[DataProductEntity2]): Behavior[Command] = {
    val behavior: Behaviors.Receive[Command] =
      Behaviors
        .receiveMessage {
          case GetEntities(limit, onlyLatest, replyTo) =>
            replyTo ! Try(GetEntitiesResponse {
              val entities = DataProductClient.getAll[DataProductEntity2](limit)
              if (onlyLatest)
                entities.groupBy(_.shortName).map { case (_, values) => values.maxBy(_.createdAt) }.toList.sorted
              else entities.sorted
            })
            Behaviors.same
          case FindEntities(params, onlyLatest, limit, replyTo) =>
            replyTo ! Try(GetEntitiesResponse {
              val entities = DataProductClient.find[DataProductEntity2, String](params, limit)
              if (onlyLatest)
                entities.groupBy(_.shortName).map { case (_, values) => values.maxBy(_.createdAt) }.toList.sorted
              else entities.sorted
            })
            Behaviors.same
          case MultiMatchFindEntities(query, fields, qType, sourceInclude, sourceExclude, limit, params, replyTo) =>
            replyTo ! Try(GetEntitiesResponse {
              DataProductClient.multiMatchFind[DataProductEntity2](
                query, fields, qType, sourceInclude, sourceExclude, limit, params)
                .groupBy(_.shortName).map { case (_, values) => values.maxBy(_.createdAt) }.toList.sorted
            })
            Behaviors.same
          case CreateEntity(entity: DataProductEntity2, replyTo) =>
            replyTo ! Try(ActionPerformed {
              entity.validateFields
              val updatedEntity = DataProductService.syncLndToData(entity)
              s"""${DataProductService.applyViewDefinition(updatedEntity)}
                 |${DataProductClient.create(updatedEntity)}""".stripMargin
            })
            Behaviors.same
          case CreateEntities(entities: List[DataProductEntity2], replyTo) =>
            replyTo ! Try(ActionPerformed {
              entities.foreach(_.validateFields)
              val updatedEntities = entities.map(DataProductService.syncLndToData(_))
              s"""${updatedEntities.map(DataProductService.applyViewDefinition).mkString("\n")}
                 |${DataProductClient.createBulk(updatedEntities)}""".stripMargin
            })
            Behaviors.same
          case UpdateEntity(entity: DataProductEntity2, replyTo) =>
            replyTo ! Try(ActionPerformed {
              entity.validateFields
              //          cleanUpExisting(id)
              s"""${DataProductService.applyViewDefinition(entity)}
                 |${DataProductClient.update(entity)}""".stripMargin
            })
            Behaviors.same
          //          case GetEntity(id, replyTo: ActorRef[Try[GetEntityResponse[DataProductEntity2]]]) =>
          case GetEntity(id, replyTo) =>
            replyTo ! Try(GetEntityResponse(DataProductClient.get[DataProductEntity2](id)))
            Behaviors.same
          case DeleteEntity(id, replyTo) =>
            replyTo ! Try(ActionPerformed {
              //          cleanUpExisting(id)
              DataProductClient.delete(id)
            })
            Behaviors.same
        }

    Behaviors
      .supervise(Behaviors.supervise(behavior)
        .onFailure[IllegalStateException](SupervisorStrategy.restart))
      .onFailure[IllegalArgumentException](SupervisorStrategy.restart)
  }

  def cleanUpExisting(id: String, indicesAction: Option[String] = None): String = {
    DataProductClient.get(id).map { entity: DataProductEntity2 =>
      //todo: delete or close indices
      indicesAction match {
        case Some("delete") => ???
        case Some("close") => ???
        case None => ???
      }
      //todo: delete of remove aliases
    }
    "Result Summary"
  }
}

