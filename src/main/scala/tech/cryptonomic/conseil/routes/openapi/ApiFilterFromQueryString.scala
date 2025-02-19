package tech.cryptonomic.conseil.routes.openapi

import cats.Functor
import cats.syntax.functor._
import endpoints.algebra
import tech.cryptonomic.conseil.tezos.ApiOperations.Filter

/** Trait containing helper functions which are necessary for parsing query parameter strings as Filter  */
trait ApiFilterFromQueryString { self: algebra.JsonEntities =>
  import tech.cryptonomic.conseil.routes.openapi.TupleFlattenHelper._
  import FlattenHigh._

  /** Query string functor adding map operation */
  implicit def qsFunctor: Functor[QueryString]

  /** Query params type alias */
  type QueryParams = (
      Option[Int],
      List[String],
      List[Int],
      List[String],
      List[String],
      List[String],
      List[String],
      List[String],
      List[String],
      List[String],
      List[String],
      List[String],
      List[String],
      Option[String],
      Option[String]
  )

  /** Function for extracting query string with query params */
  private def filterQs: QueryString[QueryParams] = {
    val raw =
      qs[Option[Int]]("limit") &
          qs[List[String]]("block_id") &
          qs[List[Int]]("block_level") &
          qs[List[String]]("block_netid") &
          qs[List[String]]("block_protocol") &
          qs[List[String]]("operation_id") &
          qs[List[String]]("operation_source") &
          qs[List[String]]("operation_destination") &
          qs[List[String]]("operation_participant") &
          qs[List[String]]("operation_kind") &
          qs[List[String]]("account_id") &
          qs[List[String]]("account_manager") &
          qs[List[String]]("account_delegate") &
          qs[Option[String]]("sort_by") &
          qs[Option[String]]("order")
    raw map (flatten(_))
  }

  /** Function for mapping query string to Filter */
  val qsFilter: QueryString[Filter] =
    filterQs.map(
      (Filter.readParams _).tupled
    )

}
