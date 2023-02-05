package ai.taiyo

import com.sksamuel.elastic4s._
import com.sksamuel.elastic4s.requests.bulk.BulkResponse
import com.sksamuel.elastic4s.requests.common.RefreshPolicy
import com.sksamuel.elastic4s.requests.indexes.IndexResponse
import com.sksamuel.elastic4s.requests.indexes.admin.RefreshIndexResponse
import com.sksamuel.elastic4s.requests.searches.{SearchHit, SearchIterator, SearchRequest, SearchResponse}
import com.sksamuel.elastic4s.requests.update.UpdateResponse
import com.typesafe.scalalogging._
import org.json4s.DefaultFormats
import org.json4s.Extraction.decompose
import org.json4s.native.JsonMethods.parse
import org.json4s.native.Serialization.write

import java.time.Instant
import scala.concurrent.duration._
import scala.util.Try


trait ESEntityUtil {
  val id: Option[String]
  val createdAt: Option[Long]
  val updatedAt: Option[Long]
}

trait ESEntityTemplate extends LazyLogging {
  val client: ElasticClient
  val indexName: String

  import com.sksamuel.elastic4s.ElasticDsl._

  implicit val formats = DefaultFormats

  def refresh(): Response[RefreshIndexResponse] = {
    client.execute(refreshIndex(indexName)).await.logRequestResult()
  }

  def getAll[T <: ESEntityUtil](limit: Int = 10)(implicit m: Manifest[T]): List[T] = {
    val resp: Response[SearchResponse] =
      client.execute {
        search(indexName).query(matchAllQuery())
          .size(limit)
      }.await.logRequestResult()

    resp.result.hits.hits.map(extract(_)).toList
  }

  def extract[T <: ESEntityUtil](searchHit: SearchHit)(implicit m: Manifest[T]): T = {
    val jsonValue = searchHit.sourceAsString
    json2Entity(jsonValue, searchHit.id)
  }

  def json2Entity[T <: ESEntityUtil](jsonValue: String, id: String)(implicit m: Manifest[T]): T = {
    parse(jsonValue)
      .merge(decompose("id" -> id))
      .extract[T]
  }

  def createBulk[T <: ESEntityUtil](entities: List[T]): String = {
    val resp: Response[BulkResponse] =
      client.execute {
        bulk {
          entities.map(entity => indexInto(indexName).doc(toESJson(entity))): _*
        }.refresh(RefreshPolicy.Immediate)
      }.await
    //.logRequestResult()

    resp match {
      case RequestSuccess(status, body, headers, result: BulkResponse) =>
        write(Map("successes" -> result.successes.length, "failures" -> result.failures.length))
      case RequestFailure(status, body, headers, error) =>
        write(error)
    }
  }

  private def toESJson[T <: ESEntityUtil](entity: T): String = {
    import org.json4s._
    import org.json4s.native.JsonMethods._
    import org.json4s.native.Serialization.write
    implicit val formats = DefaultFormats
    compact(render(
      if (entity.isInstanceOf[JsonASTEntity])
        entity.asInstanceOf[JsonASTEntity].json
          .merge(decompose("updatedAt" -> Instant.now.getEpochSecond))
      else
        parse(write(entity))
          .removeField(_._1 == "id")
          .merge(decompose("updatedAt" -> Instant.now.getEpochSecond))
    ))
  }

  def get[T <: ESEntityUtil](id: String)(implicit m: Manifest[T]): Option[T] = {
    val resp: Response[SearchResponse] =
      client.execute {
        //      search(indexName).query(termQuery("id", userId))
        search(indexName).query(idsQuery(id))
      }.await.logRequestResult()

    resp.result.hits.hits.headOption.map(extract(_))
  }

  def delete[T <: ESEntityUtil](id: String): String = {
    val resp =
      client.execute {
        deleteByQuery(indexName, idsQuery(id) /*termQuery("id", userId)*/)
      }.await.logRequestResult()

    s"${resp.result.toString} Deleted!"
  }

  def update[T <: ESEntityUtil](entity: T): String = {
    val resp: Response[UpdateResponse] =
      client.execute {
        updateById(indexName, entity.id.getOrElse(""))
          .doc(toESJson(entity))
          .refresh(RefreshPolicy.IMMEDIATE)
      }.await.logRequestResult()
    resp match {
      case RequestSuccess(status, body, headers, result: UpdateResponse) =>
        result.result
      case RequestFailure(status, body, headers, error: ElasticError) =>
        error.`type` match {
          case "document_missing_exception" =>
            logger.warn(s"Entity with id ${entity.id} does not exist. Creating new.")
            create(entity)
          case _ => error.reason
        }
    }
  }

  def create[T <: ESEntityUtil](entity: T): String = {
    val resp: Response[IndexResponse] =
      client.execute {
        indexInto(indexName)
          .doc(toESJson(entity))
          //        .doc(compact(render(parse(user.toJson()).removeField(_._1 == "id"))))
          //        .doc(compact(render(parse(user.toJson())  merge  decompose(("_id" -> user.id)))))
          //                .fields(user.toMap/*.dropWhile { case (k, v) => k == "id" }*/)
          .refresh(RefreshPolicy.Immediate)
      }.await
    //.logRequestResult()

    resp.result.result
  }

  def find[T <: ESEntityUtil, V <: Any](matchFields: Map[String, V], limit: Int = 10)(implicit m: Manifest[T]): List[T] = {
    val resp: Response[SearchResponse] = {
      client.execute {
        search(indexName).query(
          boolQuery()
            .must(matchAllQuery() :: matchFields.map { case (k, v) => matchQuery(k, v) }.toList)
        ).size(limit)
      }.await.logRequestResult()
    }
    resp.result.hits.hits.map(extract(_)).toList
  }

  def matchAny[V <: Any](matchFields: Map[String, V]): String = {
    val resp: Response[SearchResponse] = {
      client.execute {
        search(indexName).query(
          boolQuery().must(matchFields.map { case (k, v) => matchQuery(k, v) }))
      }.await.logRequestResult()
    }
    write(resp.result)
  }

  def multiMatchFind[T <: ESEntityUtil](_query: String, _fields: List[String], _type: String = "best_fields"
                                        , _sourceInclude: List[String] = Nil, _sourceExclude: List[String] = Nil
                                        , limit: Option[Int] = None
                                        , matchFields: Map[String, String] = Map.empty)
                                       (implicit m: Manifest[T]): List[T] = {
    val resp: Response[SearchResponse] = {
      client.execute {
        var srchQuery: SearchRequest = search(indexName).query(
          boolQuery()
            .must(matchAllQuery()
              :: multiMatchQuery(_query).matchType(_type).fields(_fields)
              :: matchFields.map { case (k, v) => matchQuery(k, v) }.toList)
        )
        srchQuery = if (_sourceInclude.nonEmpty) srchQuery.sourceInclude(_sourceInclude) else srchQuery
        srchQuery = if (_sourceExclude.nonEmpty) srchQuery.sourceExclude(_sourceExclude) else srchQuery
        srchQuery = if (limit.isDefined) srchQuery.size(limit.get) else srchQuery
        srchQuery
      }.await.logRequestResult()
    }
    resp.result.hits.hits.map(extract(_)).toList
  }

  def scrollAll(batchSize: Int = 10): Iterator[String] = {
    val keepAlive = "1m"
    implicit val timeout: Duration = 10.minute
    implicit object JSONHitReader extends HitReader[String] {
      override def read(hit: Hit): Try[String] = {
        Try(hit.sourceAsString)
      }
    }
    val iterator: Iterator[String] = SearchIterator.iterate[String](
      client, search(indexName)
        .matchAllQuery.keepAlive(keepAlive).size(batchSize)
    )
    iterator
  }

  implicit class RichESResponse[T](response: Response[T]) {
    def logRequestResult(): Response[T] = {
      logger.info("---- Request Results ----")
      response match {
        case RequestSuccess(status, body, headers, result: SearchResponse) =>
          logger.info(s"Search Response Result size:${result.hits.hits.toList.map(_.sourceAsString)}")
        case RequestSuccess(status, body, headers, result) =>
          logger.info(s"Search Response Result size:${result}")
        case RequestFailure(status, body, headers, error) =>
          logger.warn(s"Request Failed error:$error details:$body")
      }
      response
    }
  }

}
