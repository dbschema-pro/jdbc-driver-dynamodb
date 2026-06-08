package com.wisecoders.jdbc.dynamodb

import com.wisecoders.common_jdbc.jvm.sql.AbstractConnection
import com.wisecoders.common_jdbc.jvm.sql.parseProperties
import java.net.URI
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.Properties
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient


class DynamoDBConnection(
    url: String,
    info: Properties
) : AbstractConnection() {

    private var closed = false
    private val client: DynamoDbClient

    val clientInstance: DynamoDbClient
        get() = client

    init {
        // Merge properties from URL and provided info
        val urlProperties = url.parseProperties()
        val mixedProperties = Properties().apply {
            putAll(urlProperties)
            putAll(info)
        }

        val region = mixedProperties.getProperty("region", "us-east-1")
        val accessKeyId = mixedProperties.getProperty("accessKeyId", "dummy")
        val secretAccessKey = mixedProperties.getProperty("secretAccessKey", "dummy")
        val endpoint = mixedProperties.getProperty("endpoint") // optional

        val credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey)

        client = DynamoDbClient.builder()
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .apply { endpoint?.let { endpointOverride(URI.create(it)) } }
            .build()
    }

    override fun createStatement(): Statement {
        checkOpen()
        return DynamoDBPreparedStatement(this)
    }

    override fun prepareStatement(sql: String): PreparedStatement {
        checkOpen()
        return DynamoDBPreparedStatement(this, sql)
    }

    private fun checkOpen() {
        if (closed) {
            throw IllegalStateException("Connection is closed.")
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            client.close()
        }
    }

    override fun isClosed(): Boolean = closed

    override fun getMetaData() = DynamoDBDatabaseMetaData(client, this)
}
