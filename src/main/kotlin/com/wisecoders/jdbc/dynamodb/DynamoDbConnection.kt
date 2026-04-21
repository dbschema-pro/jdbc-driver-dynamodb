package com.wisecoders.jdbc.dynamodb

import com.wisecoders.common_jdbc.jvm.sql.AbstractConnection
import java.net.URI
import java.net.URLDecoder
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

    private val client: DynamoDbClient
    private var closed = false
    internal val endpointUrl: String

    val clientInstance: DynamoDbClient
        get() = client

    init {
        // URL format: jdbc:dynamodb://dynamodb.{region}.amazonaws.com
        // Credentials: user = accessKeyId, password = secretAccessKey
        val withoutJdbc = url.removePrefix("jdbc:")

        // Parse query params for optional overrides
        val urlProperties = Properties()
        val qIdx = withoutJdbc.indexOf('?')
        if (qIdx >= 0) {
            withoutJdbc.substring(qIdx + 1).split("&").forEach { param ->
                val eq = param.indexOf('=')
                if (eq > 0) {
                    urlProperties[URLDecoder.decode(param.substring(0, eq), "UTF-8")] =
                        URLDecoder.decode(param.substring(eq + 1), "UTF-8")
                }
            }
        }

        // Explicit properties override URL-embedded params; URL params override defaults
        val merged = Properties().apply { putAll(urlProperties); putAll(info) }

        // Extract host and port from URL (e.g. dynamodb.eu-central-1.amazonaws.com)
        val baseUrl = if (qIdx >= 0) withoutJdbc.substring(0, qIdx) else withoutJdbc
        val uri = try { URI(baseUrl) } catch (_: java.net.URISyntaxException) { null }
        val host = uri?.host?.takeIf { it.isNotBlank() }

        // Derive region: from host pattern dynamodb.{region}.amazonaws.com, or from properties
        val regionFromHost = host
            ?.let { Regex("""^dynamodb\.([^.]+(?:-[^.]+)*)\.amazonaws\.com$""").find(it) }
            ?.groupValues?.get(1)
        val region = merged.getProperty("region") ?: regionFromHost ?: "us-east-1"

        // Build endpoint URL
        val port = if (uri != null && uri.port != -1) ":${uri.port}" else ""
        val scheme = if (host == "localhost" || host == "127.0.0.1") "http" else "https"
        endpointUrl = if (host != null) "$scheme://$host$port" else ""

        // Credentials: standard JDBC user/password, with accessKeyId/secretAccessKey as aliases
        val accessKeyId     = merged.getProperty("user")            ?: merged.getProperty("accessKeyId",     "dummy")
        val secretAccessKey = merged.getProperty("password")        ?: merged.getProperty("secretAccessKey", "dummy")

        val credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey)

        client = DynamoDbClient.builder()
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .apply { if (endpointUrl.isNotEmpty()) endpointOverride(URI.create(endpointUrl)) }
            .build()
    }

    override fun createStatement(): Statement {
        if (isClosed) throw java.sql.SQLException("Connection is closed.")
        return DynamoDBPreparedStatement(this)
    }

    override fun prepareStatement(sql: String): PreparedStatement {
        if (isClosed) throw java.sql.SQLException("Connection is closed.")
        return DynamoDBPreparedStatement(this, sql)
    }

    override fun close() {
        if (!closed) {
            client.close()
            closed = true
        }
    }

    override fun isClosed(): Boolean = closed

    override fun getMetaData() = DynamoDBDatabaseMetaData(client, this)
}