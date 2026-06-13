package com.wisecoders.jdbc.dynamodb

/**
 * Singleton access to the DynamoDB Local container endpoint.
 * The actual container lifecycle is managed by DynamoDbContainerFactory (Java),
 * which isolates Kotlin from GenericContainer's JUnit-4 supertype.
 */
object DynamoDbContainer {

    val endpoint: String by lazy { DynamoDbContainerFactory.endpoint() }

    /** Build a JDBC URL that encodes the container's host:port in the authority section so
     * that parseProperties() extracts the correct endpoint (it derives endpoint from host:port,
     * not from the query param of the same name). */
    fun jdbcUrl(): String {
        val port = endpoint.substringAfterLast(":")
        return "jdbc:dynamodb://localhost:$port?region=us-east-1&accessKeyId=dummy&secretAccessKey=dummy"
    }
}
