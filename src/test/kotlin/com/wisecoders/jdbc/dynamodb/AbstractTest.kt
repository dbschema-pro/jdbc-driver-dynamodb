package com.wisecoders.jdbc.dynamodb

import com.wisecoders.common_jdbc.jvm.result_set.ListOfObjectsAsResultSet
import java.util.Properties
import org.junit.jupiter.api.Tag
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType

@Tag("docker")
abstract class AbstractTest {

    fun createClient(): DynamoDBConnection {
        val properties = Properties().apply {
            setProperty("endpoint", DynamoDbContainer.endpoint)
            setProperty("accessKeyId", "dummy")
            setProperty("secretAccessKey", "dummy")
            setProperty("region", "us-east-1")
        }
        return DynamoDBConnection("jdbc:dynamodb://localhost:8000", properties)
    }

    fun createTable(client: DynamoDbClient, tableName: String) {
        val request = CreateTableRequest.builder()
            .tableName(tableName)
            .keySchema(
                KeySchemaElement.builder().attributeName("UserId").keyType(KeyType.HASH).build()
            )
            .attributeDefinitions(
                AttributeDefinition.builder().attributeName("UserId").attributeType(ScalarAttributeType.S).build()
            )
            .provisionedThroughput(
                ProvisionedThroughput.builder()
                    .readCapacityUnits(5)
                    .writeCapacityUnits(5)
                    .build()
            )
            .build()

        try {
            val response = client.createTable(request)
            println("Table created: ${response.tableDescription().tableName()}")
        } catch (e: Exception) {
            println("Error creating table: ${e.message}")
            return
        }
    }

    fun dropTable(client: DynamoDbClient, tableName: String) {
        val request = DeleteTableRequest.builder()
            .tableName(tableName)
            .build()

        try {
            val response = client.deleteTable(request)
            println("Table deleted: ${response.tableDescription()?.tableName()}")
        } catch (e: Exception) {
            println("Error deleting table: ${e.message}")
        }
    }

    fun queryTable(
        client: DynamoDbClient,
        tableName: String,
    ): ListOfObjectsAsResultSet {

        val result = client.scan { it.tableName(tableName) }

        val columns = mutableSetOf<String>()
        result.items().forEach { item ->
            columns += item.keys
            /*val mapper = ObjectMapper().apply {
            enable(SerializationFeature.INDENT_OUTPUT)
        }*/

            printItem(item)
        }

        return ListOfObjectsAsResultSet(result.items())
    }

    fun insertData(client: DynamoDbClient, tableName: String) {
        val item = mapOf(
            "UserId" to AttributeValue.fromS("user-001"),
            "Name" to AttributeValue.fromS("Alice"),
            "Age" to AttributeValue.fromN("29"),

            "Tags" to AttributeValue.fromL(
                listOf("developer", "blogger").map { AttributeValue.fromS(it) }
            ),

            "Sessions" to AttributeValue.fromL(
                listOf(
                    mapOf(
                        "Date" to AttributeValue.fromS("2025-07-01"),
                        "Device" to AttributeValue.fromS("iPhone")
                    ),
                    mapOf(
                        "Date" to AttributeValue.fromS("2025-07-20"),
                        "Device" to AttributeValue.fromS("MacBook")
                    )
                ).map { AttributeValue.fromM(it) }
            ),

            "Address" to AttributeValue.fromM(
                mapOf(
                    "Street" to AttributeValue.fromS("123 Main St"),
                    "City" to AttributeValue.fromS("Berlin"),
                    "Zip" to AttributeValue.fromN("10115")
                )
            )
        )

        val putItemRequest = PutItemRequest.builder().tableName(tableName).item(item).build()
        client.putItem(putItemRequest)
    }


    fun printItem(item: Map<String, AttributeValue>) {
        println("Item:")
        for ((key, value) in item) {
            val actualValue = when {
                value.hasL() -> value.l()
                value.hasM() -> value.m()
                value.hasBs() -> value.bs()
                value.hasNs() -> value.ns()
                value.hasSs() -> value.ss()
                else -> "Unsupported type"
            }
            println("  $key: $actualValue")
        }
    }

}
