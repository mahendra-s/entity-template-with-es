package ai.taiyo.mc3

import ai.taiyo.util.TaiyoServeConfig
import ai.taiyo.{BaseClient, ESEntityTemplate, ElasticRestClient}
import com.sksamuel.elastic4s.requests.indexes.CreateIndexResponse
import com.sksamuel.elastic4s.{RequestFailure, RequestSuccess, Response}

object DataProductClient extends BaseClient with ElasticRestClient with ESEntityTemplate {
  val indexName = s"data-product-entity-info-${TaiyoServeConfig.env}"
  val docType = "_doc"

  import com.sksamuel.elastic4s.ElasticDsl._

  initialize()

  override def initialize(): Unit = {
    println(s"creating elasticsearch $indexName")
    val resp: Response[CreateIndexResponse] = client.execute {
      createIndex(indexName).mapping {
        properties(
          /* KeywordField("name"),
           KeywordField("email"),
           KeywordField("accessKey"),
           KeywordField("secretKey"),
           NestedField("notifications",
             properties = Seq(
               KeywordField("nId"),
               KeywordField("message"),
               KeywordField("status"),
               DateField("createdAt"),
               DateField("updatedAt"))),
           NestedField("accessTokens",
             properties = Seq(
               KeywordField("accessTokenID"),
               NestedField("dataProducts",
                 properties = Seq(KeywordField("name"))),
               KeywordField("message"),
               KeywordField("status"),
               DateField("createdAt"),
               DateField("updatedAt"))),
           DateField("createdAt"),
           DateField("updatedAt")*/
        )
      }
    }.await
    resp match {
      case failure: RequestFailure => println("We failed " + failure.error)
      case results: RequestSuccess[CreateIndexResponse] => println(results.result)
      case results: RequestSuccess[_] => println(results.result)
    }
  }


  override def destroy(): Unit = client.close()

}
