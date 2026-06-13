package com.wisecoders.jdbc.dynamodb

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementRequest

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbTests : AbstractTest(){

    private val dynamoDbConnection: DynamoDBConnection = createClient()
    private val tableName = "Users"

    @BeforeAll
    fun setupClient() {
        val dynamoDbClient = dynamoDbConnection.clientInstance

        dropTable(dynamoDbClient, tableName)
        createTable(dynamoDbClient, tableName)
        insertData(dynamoDbClient, tableName)
        queryTable(dynamoDbClient, tableName)
    }

    @Test
    fun `test show metadata`(){
        val metaData = dynamoDbConnection.metaData

        val tables = mutableSetOf<String>().apply {
            metaData.getTables(null, null, tableName, null).use { rs ->
                while (rs.next()) {
                    add(rs.getString("TABLE_NAME"))
                }
            }
        }
        assertTrue( tables.contains(tableName ))

        val columns = mutableSetOf<Triple<String,String,Int>>().apply {
            metaData.getColumns(null, null, tableName, null).use { rs ->
                while (rs.next()) {
                    add(Triple( rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE")))
                }
            }
        }
        assertThat( columns).contains(
            Triple("UserId", "VARCHAR", 0),   // key attribute type is VARCHAR (S = string)
            Triple("Address", "JSON", 0),      // sampled map attribute uses JSON as type name
        )

    }

    @Test
    fun `list items`(){
        val limit = 20
        val sql = "SELECT * FROM Users"

        val request = ExecuteStatementRequest.builder()
            .statement(sql)
            .build()

        val response = dynamoDbConnection.clientInstance.executeStatement(request)

        println("Listing up to $limit users:")
        response.items().forEachIndexed { index, item ->
            val userId = item["UserId"]?.s() ?: "N/A"
            val name = item["Name"]?.s() ?: "N/A"
            println("${index + 1}. UserId: $userId, Name: $name")
        }
    }

    @Test
    fun `list items2`(){
        val limit = 20
        val sql = "SELECT * FROM Users"

        val rs = dynamoDbConnection.prepareStatement( sql ).executeQuery()
        while ( rs.next() ){
            println ( rs.getString(1 ) )
        }
    }

    @AfterAll
    fun tearDown() {
        dynamoDbConnection.close()
    }


}
