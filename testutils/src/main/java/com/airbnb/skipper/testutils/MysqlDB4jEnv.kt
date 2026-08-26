package com.airbnb.skipper.testutils

import ch.vorburger.mariadb4j.DB
import ch.vorburger.mariadb4j.DBConfigurationBuilder

class MysqlDB4jEnv(
    private val dbName: String
) {
    private var db: DB? = null
    private var databaseUrl: String? = null

    val mariaDbJdbcUrl: String
        get() = checkNotNull(databaseUrl)

    val mysqlJdbcUrl: String
        get() = mariaDbJdbcUrl.replace("jdbc:mariadb:", "jdbc:mysql:")

    fun start() {
        val configuration =
            DBConfigurationBuilder.newBuilder()
                .addArg("--user=root")
                .build()
        val embeddedDb = DB.newEmbeddedDB(configuration)
        db = embeddedDb
        embeddedDb.start()
        embeddedDb.createDB(dbName)
        databaseUrl = configuration.getURL(dbName)
    }

    fun stop() {
        db?.stop()
        db = null
        databaseUrl = null
    }
}
