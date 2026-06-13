package com.wisecoders.jdbc.dynamodb

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbTestQueries : AbstractTest(){

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

    @AfterAll
    @Test
    fun `list items2`(){
        val sql = "SELECT * FROM $tableName"

        val rs = dynamoDbConnection.prepareStatement( sql ).executeQuery()
        while ( rs.next() ){
            val res = rs.getString(1 )
            println ( res )
        }
    }


}
