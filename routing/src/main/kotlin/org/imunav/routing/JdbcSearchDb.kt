package org.imunav.routing

import org.imunav.core.geo.GeoPoint
import org.imunav.core.search.AddressRow
import org.imunav.core.search.PlaceRow
import org.imunav.core.search.SearchDb
import org.imunav.core.search.SearchOrder
import org.imunav.core.search.StreetAddressRow
import org.imunav.core.search.StreetRow
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** JDBC implementation of [SearchDb] (desktop tools and tests). */
class JdbcSearchDb(file: File) :
    SearchDb,
    AutoCloseable {
    private val c: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    override fun places(match: String, limit: Int, near: GeoPoint?): List<PlaceRow> {
        val order = SearchOrder.near("p", near)
        return c.prepareStatement(
            "SELECT p.id,p.name,p.kind,p.lat,p.lon,p.population FROM place_fts f JOIN place p ON p.id=f.rowid WHERE place_fts MATCH ?${order.sql} LIMIT ?",
        ).use { ps ->
            ps.setString(1, match)
            (order.arguments + limit.toString()).forEachIndexed { index, argument -> ps.setString(index + 2, argument) }
            ps.executeQuery().use { rs ->
                generateSequence { if (rs.next()) PlaceRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDouble(4), rs.getDouble(5), rs.getInt(6)) else null }.toList()
            }
        }
    }

    override fun streets(match: String, limit: Int, placeIds: Collection<Long>?, near: GeoPoint?): List<StreetRow> {
        val order = SearchOrder.near("s", near)
        val filter = placeIds?.takeIf { it.isNotEmpty() }?.let { " AND s.place_id IN (${it.joinToString(",")})" }.orEmpty()
        return c.prepareStatement(
            "SELECT s.id,s.name,s.place_id,p.name,s.lat,s.lon FROM street_fts f JOIN street s ON s.id=f.rowid LEFT JOIN place p ON p.id=s.place_id " +
                "WHERE street_fts MATCH ?$filter${order.sql} LIMIT ?",
        ).use { ps ->
            ps.setString(1, match)
            (order.arguments + limit.toString()).forEachIndexed { index, argument -> ps.setString(index + 2, argument) }
            ps.executeQuery().use { rs ->
                generateSequence {
                    if (rs.next()) StreetRow(rs.getLong(1), rs.getString(2), rs.getLong(3).takeIf { !rs.wasNull() }, rs.getString(4), rs.getDouble(5), rs.getDouble(6)) else null
                }.toList()
            }
        }
    }

    override fun addresses(streetId: Long, number: String): List<AddressRow> =
        c.prepareStatement("SELECT number,lat,lon FROM addr WHERE street_id=? AND (number=? OR number LIKE ?) LIMIT 5").use { ps ->
            ps.setLong(1, streetId)
            ps.setString(2, number)
            ps.setString(3, "$number %")
            ps.executeQuery().use { rs -> generateSequence { if (rs.next()) AddressRow(rs.getString(1), rs.getDouble(2), rs.getDouble(3)) else null }.toList() }
        }

    override fun batchAddresses(streetIds: Collection<Long>, number: String): List<StreetAddressRow> {
        if (streetIds.isEmpty()) return emptyList()
        return c.prepareStatement("SELECT street_id,number,lat,lon FROM addr WHERE street_id IN (${streetIds.joinToString(",")}) AND (number=? OR number LIKE ?)").use { ps ->
            ps.setString(1, number)
            ps.setString(2, "$number %")
            ps.executeQuery().use { rs ->
                generateSequence { if (rs.next()) StreetAddressRow(rs.getLong(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4)) else null }.toList()
            }
        }
    }

    override fun close() = c.close()
}
