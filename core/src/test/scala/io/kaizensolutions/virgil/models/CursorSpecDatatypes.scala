package io.kaizensolutions.virgil.models

import com.datastax.oss.driver.api.core.cql.Row
import io.kaizensolutions.virgil.CQL
import io.kaizensolutions.virgil.MutationResult
import io.kaizensolutions.virgil.annotations.CqlColumn
import io.kaizensolutions.virgil.cql._
import io.kaizensolutions.virgil.dsl._

import java.net.InetAddress

object CursorSpecDatatypes {
  final case class CursorExampleRow(
    id: Long,
    name: String,
    age: Short,
    @CqlColumn("may_be_empty") mayBeEmpty: Option[String],
    @CqlColumn("addresses") pastAddresses: List[CursorUdtAddress]
  )

  object CursorExampleRow extends CursorExampleRowInstances {
    val tableName = "cursorspec_cursorexampletable"

    def truncate: CQL[MutationResult] = CQL.truncate(tableName)

    def insert(row: CursorExampleRow): CQL[MutationResult] =
      InsertBuilder(tableName)
        .values(
          "id"           -> row.id,
          "name"         -> row.name,
          "age"          -> row.age,
          "addresses"    -> row.pastAddresses,
          "may_be_empty" -> row.mayBeEmpty
        )
        .build

    def select(id: Long): CQL[Row] = {
      val cql = cql"SELECT * FROM " ++ tableName.asCql ++ cql" WHERE id = $id"
      cql.query
    }
  }

  final case class CursorUdtAddress(street: String, city: String, state: String, zip: String, note: CursorUdtNote)
  object CursorUdtAddress extends CursorUdtAddressInstances

  final case class CursorUdtNote(data: String, ip: InetAddress)
  object CursorUdtNote extends CursorUdtNoteInstances
}
