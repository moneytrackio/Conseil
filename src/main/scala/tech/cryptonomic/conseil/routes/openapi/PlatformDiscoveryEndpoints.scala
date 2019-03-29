package tech.cryptonomic.conseil.routes.openapi

import endpoints.algebra
import tech.cryptonomic.conseil.generic.chain.PlatformDiscoveryTypes

/** Trait containing platform discovery endpoints definition */
trait PlatformDiscoveryEndpoints
    extends algebra.Endpoints
    with PlatformDiscoveryJsonSchemas
    with algebra.JsonSchemaEntities {

  /** Common path for metadata endpoints */
  private val commonPath = path / "v2" / "metadata"

  /** Metadata platforms endpoint */
  def platformsEndpoint: Endpoint[String, List[PlatformDiscoveryTypes.Platform]] =
    endpoint(
      request = get(url = commonPath / "platforms", headers = header("apiKey")),
      response = jsonResponse[List[PlatformDiscoveryTypes.Platform]](
        docs = Some("Metadata endpoint for listing available platforms")
      ),
      tags = List("Metadata")
    )

  /** Metadata networks endpoint */
  def networksEndpoint: Endpoint[(String, String), Option[List[PlatformDiscoveryTypes.Network]]] =
    endpoint(
      request = get(url = commonPath / segment[String](name = "platforms") / "networks", headers = header("apiKey")),
      response = jsonResponse[List[PlatformDiscoveryTypes.Network]](
        docs = Some("Metadata endpoint for listing available networks")
      ).orNotFound(Some("Not found")),
      tags = List("Metadata")
    )

  /** Metadata entities endpoint */
  def entitiesEndpoint: Endpoint[(String, String, String), Option[List[PlatformDiscoveryTypes.Entity]]] =
    endpoint(
      request = get(
        url = commonPath / segment[String](name = "platform") / segment[String](name = "network") / "entities",
        headers = header("apiKey")
      ),
      response = jsonResponse[List[PlatformDiscoveryTypes.Entity]](
        docs = Some("Metadata endpoint for listing available entities")
      ).orNotFound(Some("Not found")),
      tags = List("Metadata")
    )

  /** Metadata attributes endpoint */
  def attributesEndpoint: Endpoint[((String, String, String), String), Option[List[PlatformDiscoveryTypes.Attribute]]] =
    endpoint(
      request = get(
        url = commonPath / segment[String](name = "platform") / segment[String](name = "network") / segment[String](
                name = "entity"
              ) / "attributes",
        headers = header("apiKey")
      ),
      response = jsonResponse[List[PlatformDiscoveryTypes.Attribute]](
        docs = Some("Metadata endpoint for listing available attributes")
      ).orNotFound(Some("Not found")),
      tags = List("Metadata")
    )

  /** Metadata attributes values endpoint */
  def attributesValuesEndpoint: Endpoint[((String, String, String), String, String), Option[List[String]]] =
    endpoint(
      request = get(
        url = commonPath / segment[String](name = "platform") / segment[String](name = "network") / segment[String](
                name = "entity"
              ) / segment[String](name = "attribute"),
        headers = header("apiKey")
      ),
      response = jsonResponse[List[String]](
        docs = Some("Metadata endpoint for listing available distinct values for given attribute")
      ).orNotFound(Some("Not found")),
      tags = List("Metadata")
    )

  /** Metadata attributes values with filter endpoint */
  def attributesValuesWithFilterEndpoint: Endpoint[(((String, String, String), String, String), String), Option[
    List[String]
  ]] =
    endpoint(
      request = get(
        url = commonPath / segment[String](name = "platform") / segment[String](name = "network") / segment[String](
                name = "entity"
              ) / segment[String](name = "attribute") / segment[String](name = "filter"),
        headers = header("apiKey")
      ),
      response = jsonResponse[List[String]](
        docs = Some("Metadata endpoint for listing available distinct values for given attribute filtered")
      ).orNotFound(Some("Not found")),
      tags = List("Metadata")
    )

}
