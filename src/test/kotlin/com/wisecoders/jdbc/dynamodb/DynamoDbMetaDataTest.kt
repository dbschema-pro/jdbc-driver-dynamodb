package com.wisecoders.jdbc.dynamodb

import java.sql.Connection
import java.sql.DriverManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.Projection
import software.amazon.awssdk.services.dynamodb.model.ProjectionType
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType

/**
 * Integration tests for reverse-engineering DynamoDB schema via DatabaseMetaData.
 * Verifies getTables, getColumns, getPrimaryKeys, and getIndexInfo against tables
 * created in a DynamoDB Local container managed by Testcontainers.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbMetaDataTest {

    private lateinit var connection: Connection
    private lateinit var sdkClient: DynamoDbClient

    private val usersTable = "MetaUsers"
    private val ordersTable = "MetaOrders"

    @BeforeAll
    fun setUp() {
        Class.forName("com.wisecoders.jdbc.dynamodb.JdbcDriver")
        connection = DriverManager.getConnection(DynamoDbContainer.jdbcUrl(), null, null)
        sdkClient = (connection as DynamoDBConnection).clientInstance

        createUsersTable()
        populateUsersTable()
        createOrdersTableWithGsi()
    }

    @AfterAll
    fun tearDown() {
        connection.close()
    }

    // -------------------------------------------------------------------------
    // getTables
    // -------------------------------------------------------------------------

    @Test
    fun `getTables returns both created tables`() {
        val meta = connection.metaData
        val found = mutableSetOf<String>()
        meta.getTables(null, null, null, null).use { rs ->
            while (rs.next()) found.add(rs.getString("TABLE_NAME"))
        }
        assertThat(found).contains(usersTable, ordersTable)
    }

    @Test
    fun `getTables with name filter returns only matching table`() {
        val meta = connection.metaData
        val found = mutableSetOf<String>()
        meta.getTables(null, null, usersTable, null).use { rs ->
            while (rs.next()) found.add(rs.getString("TABLE_NAME"))
        }
        assertThat(found).contains(usersTable)
        assertThat(found).doesNotContain(ordersTable)
    }

    // -------------------------------------------------------------------------
    // getColumns
    // -------------------------------------------------------------------------

    @Test
    fun `getColumns returns hash key attribute for users table`() {
        val meta = connection.metaData
        val columnNames = mutableSetOf<String>()
        meta.getColumns(null, null, usersTable, null).use { rs ->
            while (rs.next()) {
                if (rs.getString("TABLE_NAME") == usersTable)
                    columnNames.add(rs.getString("COLUMN_NAME"))
            }
        }
        // UserId is the HASH key declared in the key schema
        assertThat(columnNames).contains("UserId")
    }

    @Test
    fun `getColumns includes sampled json attributes from inserted items`() {
        val meta = connection.metaData
        val columnNames = mutableSetOf<String>()
        meta.getColumns(null, null, usersTable, null).use { rs ->
            while (rs.next()) {
                if (rs.getString("TABLE_NAME") == usersTable)
                    columnNames.add(rs.getString("COLUMN_NAME"))
            }
        }
        // Name, Age, Address were inserted as item attributes
        assertThat(columnNames).contains("Name", "Age", "Address")
    }

    @Test
    fun `getColumns for orders table includes hash and range keys`() {
        val meta = connection.metaData
        val columnNames = mutableSetOf<String>()
        meta.getColumns(null, null, ordersTable, null).use { rs ->
            while (rs.next()) {
                if (rs.getString("TABLE_NAME") == ordersTable)
                    columnNames.add(rs.getString("COLUMN_NAME"))
            }
        }
        assertThat(columnNames).contains("OrderId", "CreatedAt")
    }

    // -------------------------------------------------------------------------
    // getPrimaryKeys
    // -------------------------------------------------------------------------

    @Test
    fun `getPrimaryKeys returns hash key for users table`() {
        val meta = connection.metaData
        val pks = mutableListOf<String>()
        meta.getPrimaryKeys(null, null, usersTable).use { rs ->
            while (rs.next()) pks.add(rs.getString("COLUMN_NAME"))
        }
        assertThat(pks).containsExactly("UserId")
    }

    @Test
    fun `getPrimaryKeys returns hash and range keys for orders table`() {
        val meta = connection.metaData
        val pks = mutableListOf<String>()
        meta.getPrimaryKeys(null, null, ordersTable).use { rs ->
            while (rs.next()) pks.add(rs.getString("COLUMN_NAME"))
        }
        assertThat(pks).contains("OrderId", "CreatedAt")
    }

    // -------------------------------------------------------------------------
    // getIndexInfo (GSI on ordersTable)
    // -------------------------------------------------------------------------

    @Test
    fun `getIndexInfo returns GSI key attribute for orders table`() {
        val meta = connection.metaData
        val indexColumns = mutableSetOf<String>()
        meta.getIndexInfo(null, null, ordersTable, false, false).use { rs ->
            while (rs.next()) indexColumns.add(rs.getString("COLUMN_NAME"))
        }
        assertThat(indexColumns).contains("CustomerId")
    }

    // -------------------------------------------------------------------------
    // Table creation via JS script through prepareStatement
    // -------------------------------------------------------------------------

    @Test
    fun `create table via JS script appears in getTables`() {
        val scriptTable = "ScriptTable"
        // Drop if it exists from a previous run
        try { sdkClient.deleteTable { it.tableName(scriptTable) } } catch (_: Exception) {}

        val script = """
            const CreateTableRequest = Java.type("software.amazon.awssdk.services.dynamodb.model.CreateTableRequest");
            const KeySchemaElement   = Java.type("software.amazon.awssdk.services.dynamodb.model.KeySchemaElement");
            const KeyType            = Java.type("software.amazon.awssdk.services.dynamodb.model.KeyType");
            const AttributeDefinition = Java.type("software.amazon.awssdk.services.dynamodb.model.AttributeDefinition");
            const ScalarAttributeType = Java.type("software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType");
            const ProvisionedThroughput = Java.type("software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput");
            var request = CreateTableRequest.builder()
                .tableName("$scriptTable")
                .keySchema(KeySchemaElement.builder().attributeName("Id").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("Id").attributeType(ScalarAttributeType.S).build())
                .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(5).writeCapacityUnits(5).build())
                .build();
            var response = client.createTable(request);
            print("Table created: " + response.tableDescription().tableName());
        """.trimIndent()

        connection.prepareStatement(script).executeQuery()

        val found = mutableSetOf<String>()
        connection.metaData.getTables(null, null, scriptTable, null).use { rs ->
            while (rs.next()) found.add(rs.getString("TABLE_NAME"))
        }
        assertThat(found).contains(scriptTable)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createUsersTable() {
        try { sdkClient.deleteTable { it.tableName(usersTable) } } catch (_: Exception) {}
        sdkClient.createTable(
            CreateTableRequest.builder()
                .tableName(usersTable)
                .keySchema(
                    KeySchemaElement.builder().attributeName("UserId").keyType(KeyType.HASH).build()
                )
                .attributeDefinitions(
                    AttributeDefinition.builder().attributeName("UserId").attributeType(ScalarAttributeType.S).build()
                )
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build()
        )
    }

    private fun populateUsersTable() {
        sdkClient.putItem(
            PutItemRequest.builder()
                .tableName(usersTable)
                .item(
                    mapOf(
                        "UserId"  to AttributeValue.fromS("u-1"),
                        "Name"    to AttributeValue.fromS("Alice"),
                        "Age"     to AttributeValue.fromN("30"),
                        "Address" to AttributeValue.fromM(
                            mapOf(
                                "Street" to AttributeValue.fromS("1 Main St"),
                                "City"   to AttributeValue.fromS("Berlin")
                            )
                        )
                    )
                )
                .build()
        )
    }

    private fun createOrdersTableWithGsi() {
        try { sdkClient.deleteTable { it.tableName(ordersTable) } } catch (_: Exception) {}
        sdkClient.createTable(
            CreateTableRequest.builder()
                .tableName(ordersTable)
                .keySchema(
                    KeySchemaElement.builder().attributeName("OrderId").keyType(KeyType.HASH).build(),
                    KeySchemaElement.builder().attributeName("CreatedAt").keyType(KeyType.RANGE).build()
                )
                .attributeDefinitions(
                    AttributeDefinition.builder().attributeName("OrderId").attributeType(ScalarAttributeType.S).build(),
                    AttributeDefinition.builder().attributeName("CreatedAt").attributeType(ScalarAttributeType.S).build(),
                    AttributeDefinition.builder().attributeName("CustomerId").attributeType(ScalarAttributeType.S).build()
                )
                .globalSecondaryIndexes(
                    GlobalSecondaryIndex.builder()
                        .indexName("CustomerIndex")
                        .keySchema(
                            KeySchemaElement.builder().attributeName("CustomerId").keyType(KeyType.HASH).build()
                        )
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build()
                )
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build()
        )
    }
}
