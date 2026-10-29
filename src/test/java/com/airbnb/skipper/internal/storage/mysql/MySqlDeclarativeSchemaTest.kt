package com.airbnb.skipper.internal.storage.mysql

import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.testutils.MySqlTestSetupExtension
import java.io.File
import java.sql.Connection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * The final `CREATE TABLE`s in `db/schema/mysql` must build exactly the tables the `db/migration`
 * files build, minus `schema_migrations`.
 */
@ExtendWith(MySqlTestSetupExtension::class)
class MySqlDeclarativeSchemaTest {
    @Test
    fun declarativeSchemaMatchesMigrations() {
        MySqlTestSetupExtension.DB_DATA_SOURCE!!.connection.use { conn ->
            val files = File(javaClass.classLoader.getResource(SCHEMA_DIR)!!.toURI()).listFiles()!!
            files.forEach { file ->
                val sql = file.readText().replace("\${tablePrefix}", DECLARATIVE_PREFIX)
                conn.createStatement().use { it.execute(sql) }
            }

            val migrated = describe(conn, SkipperConfig.DEFAULT_TABLE_PREFIX) - "schema_migrations"
            assertEquals(migrated, describe(conn, DECLARATIVE_PREFIX))
        }
    }

    /**
     * Each table under [prefix], without the prefix, mapped to its options, columns and indexes.
     * Rows are sorted after the prefix is removed, so the same tables under two prefixes compare equal.
     */
    private fun describe(
        conn: Connection,
        prefix: String
    ): Map<String, List<String>> {
        val shapes = mutableMapOf<String, MutableList<String>>()

        fun collect(sql: String) {
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, "$prefix%")
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val table = rs.getString(1).removePrefix(prefix)
                        val row = (2..rs.metaData.columnCount).map { rs.getString(it)?.replace(prefix, "") }
                        shapes.getOrPut(table) { mutableListOf() }.add(row.toString())
                    }
                }
            }
        }
        collect(
            "SELECT TABLE_NAME, ENGINE, TABLE_COLLATION, CREATE_OPTIONS, TABLE_COMMENT FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE ?",
        )
        collect(
            "SELECT TABLE_NAME, ORDINAL_POSITION, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, " +
                "EXTRA, CHARACTER_SET_NAME, COLLATION_NAME, COLUMN_COMMENT FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE ?",
        )
        collect(
            "SELECT TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE, SUB_PART, COLLATION, INDEX_TYPE " +
                "FROM information_schema.STATISTICS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE ?",
        )
        return shapes.mapValues { (_, rows) -> rows.sorted() }
    }

    companion object {
        private const val SCHEMA_DIR = "db/schema/mysql"
        private const val DECLARATIVE_PREFIX = "declarative_"
    }
}
