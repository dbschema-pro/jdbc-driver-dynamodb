package com.wisecoders.jdbc.dynamodb

import java.sql.Connection
import java.sql.DriverManager
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbCreateTable : AbstractTest() {

    private lateinit var jdbcConnection: Connection

    @BeforeAll
    fun setupClient() {
        Class.forName("com.wisecoders.jdbc.dynamodb.JdbcDriver")
        jdbcConnection = DriverManager.getConnection(DynamoDbContainer.jdbcUrl(), null, null)
    }

    @Test
    fun `create table using script`(){
        val tableName = "Movies"

        //jdbcConnection.prepareStatement("DROP TABLE ${tableName}").executeQuery()

        val ps = jdbcConnection.prepareStatement( getCreateTableScript( tableName ))
        ps.executeQuery()

        val tables = mutableSetOf<String>().apply {
            jdbcConnection.metaData.getTables(null, null, tableName, null).use { rs ->
                while (rs.next()) {
                    add(rs.getString("TABLE_NAME"))
                }
            }
        }
        assertTrue( tables.contains(tableName ))
    }

    companion object{
        const val jsScript = """
                const CreateTableRequest      = Java.type("software.amazon.awssdk.services.dynamodb.model.CreateTableRequest");
                const KeySchemaElement        = Java.type("software.amazon.awssdk.services.dynamodb.model.KeySchemaElement");
                const KeyType                 = Java.type("software.amazon.awssdk.services.dynamodb.model.KeyType");
                const AttributeDefinition     = Java.type("software.amazon.awssdk.services.dynamodb.model.AttributeDefinition");
                const ScalarType              = Java.type("software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType");
                const ProvisionedThroughput   = Java.type("software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput");
                const LocalSecondaryIndex     = Java.type("software.amazon.awssdk.services.dynamodb.model.LocalSecondaryIndex");
                const GlobalSecondaryIndex    = Java.type("software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex");
                const Projection              = Java.type("software.amazon.awssdk.services.dynamodb.model.Projection");
                const ProjectionType          = Java.type("software.amazon.awssdk.services.dynamodb.model.ProjectionType");
                const StreamSpecification     = Java.type("software.amazon.awssdk.services.dynamodb.model.StreamSpecification");
                const StreamViewType          = Java.type("software.amazon.awssdk.services.dynamodb.model.StreamViewType");
                const Tag                     = Java.type("software.amazon.awssdk.services.dynamodb.model.Tag");
                const BillingMode             = Java.type("software.amazon.awssdk.services.dynamodb.model.BillingMode");

                // 🔹 Helpers
                function keyAttr(name, type) {
                  return KeySchemaElement.builder().attributeName(name).keyType(type).build();
                }

                function attrDef(name, type) {
                  return AttributeDefinition.builder().attributeName(name).attributeType(type).build();
                }

                function throughput(read, write) {
                  return ProvisionedThroughput.builder()
                      .readCapacityUnits(read)
                      .writeCapacityUnits(write)
                      .build();
                }

                function lsi(name, keyAttrs, projectionType) {
                  return LocalSecondaryIndex.builder()
                      .indexName(name)
                      .keySchema(keyAttrs)
                      .projection(Projection.builder().projectionType(projectionType).build())
                      .build();
                }

                function gsi(name, keyAttrs, projectionType, rcus, wcus) {
                  return GlobalSecondaryIndex.builder()
                      .indexName(name)
                      .keySchema(keyAttrs)
                      .projection(Projection.builder().projectionType(projectionType).build())
                      .provisionedThroughput(throughput(rcus, wcus))
                      .build();
                }

                function streamSpec(viewType) {
                  return StreamSpecification.builder()
                      .streamEnabled(true)
                      .streamViewType(viewType)
                      .build();
                }

                function tag(key, value) {
                  return Tag.builder().key(key).value(value).build();
                }

            """
    }

    fun getCreateTableScript(tableName: String):String = """
// This script expects that the JDBC driver provides a "ddb" variable
// which is a software.amazon.awssdk.services.dynamodb.DynamoDbClient

// Import Java classes
const CreateTableRequest = Java.type("software.amazon.awssdk.services.dynamodb.model.CreateTableRequest");
const KeySchemaElement = Java.type("software.amazon.awssdk.services.dynamodb.model.KeySchemaElement");
const KeyType = Java.type("software.amazon.awssdk.services.dynamodb.model.KeyType");
const AttributeDefinition = Java.type("software.amazon.awssdk.services.dynamodb.model.AttributeDefinition");
const ScalarAttributeType = Java.type("software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType");
const ProvisionedThroughput = Java.type("software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput");

// Build the request
var request = CreateTableRequest.builder()
    .tableName("Movies")
    .keySchema(
        KeySchemaElement.builder().attributeName("year").keyType(KeyType.HASH).build(),
        KeySchemaElement.builder().attributeName("title").keyType(KeyType.RANGE).build()
    )
    .attributeDefinitions(
        AttributeDefinition.builder().attributeName("year").attributeType(ScalarAttributeType.N).build(),
        AttributeDefinition.builder().attributeName("title").attributeType(ScalarAttributeType.S).build()
    )
    .provisionedThroughput(
        ProvisionedThroughput.builder().readCapacityUnits(5).writeCapacityUnits(5).build()
    )
    .build();

// Execute
var response = client.createTable(request);
print("Table created: " + response.tableDescription().tableName());

    """.trimIndent()
}
