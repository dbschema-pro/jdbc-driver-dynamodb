package com.wisecoders.jdbc.dynamodb

import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.util.Properties
import java.util.logging.Logger

class JdbcDriver : Driver {

    override fun connect(url: String, info: Properties): Connection? {
        if (!acceptsURL(url)) {
            return null
        }
        return DynamoDBConnection(url, info)
    }

    override fun acceptsURL(url: String): Boolean {
        return url.startsWith("jdbc:dynamodb:")
    }

    override fun getPropertyInfo(url: String, info: Properties): Array<DriverPropertyInfo> {
        return arrayOf(
            DriverPropertyInfo("region", info.getProperty("region", "us-east-1")).apply {
                description = "AWS Region"
                required = true
            },
            DriverPropertyInfo("accessKeyId", info.getProperty("accessKeyId")).apply {
                description = "AWS Access Key ID"
                required = true
            },
            DriverPropertyInfo("secretAccessKey", info.getProperty("secretAccessKey")).apply {
                description = "AWS Secret Access Key"
                required = true
            }
        )
    }

    override fun getMajorVersion(): Int = 1

    override fun getMinorVersion(): Int = 0

    override fun jdbcCompliant(): Boolean = true

    override fun getParentLogger(): Logger? {
        return null
    }

    companion object {
        init {
            DriverManager.registerDriver(JdbcDriver())
        }
    }
}
