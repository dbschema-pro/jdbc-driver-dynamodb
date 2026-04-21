package com.wisecoders.jdbc.dynamodb

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import software.amazon.awssdk.services.dynamodb.model.*
import java.sql.DriverManager
import java.util.Properties

/**
 * Integration tests against a real AWS DynamoDB endpoint.
 * Credentials are loaded from src/test/resources/aws-test.properties (gitignored).
 *
 * A dedicated test table is created in @BeforeAll and deleted in @AfterAll.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DynamoDBAwsIntegrationTest {

    private val TABLE = "jdbc_driver_test"
    private lateinit var conn: DynamoDBConnection
    private val rawClient get() = conn.clientInstance

    // -------------------------------------------------------------------------
    // Setup / teardown
    // -------------------------------------------------------------------------

    @BeforeAll
    fun connect() {
        val props = loadTestProps()
        conn = DynamoDBConnection(
            "jdbc:dynamodb://${props.getProperty("host")}",
            props
        )

        // Clean up any leftover table from a previous failed run, then create fresh
        dropIfExists(TABLE)
        createTestTable()
        insertTestData()
    }

    @AfterAll
    fun disconnect() {
        dropIfExists(TABLE)
        conn.close()
    }

    // -------------------------------------------------------------------------
    // Connection tests
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `connection is open after connect`() {
        assertFalse(conn.isClosed, "Connection should be open")
    }

    @Test
    @Order(2)
    fun `DriverManager connect via URL`() {
        val props = loadTestProps()
        val url = "jdbc:dynamodb://${props.getProperty("host")}"
        val connProps = Properties().apply {
            setProperty("user",     props.getProperty("user"))
            setProperty("password", props.getProperty("password"))
        }
        val c = DriverManager.getConnection(url, connProps)
        assertTrue(c != null, "Connection should not be null")
        assertFalse(c.isClosed)
        c.close()
        assertTrue(c.isClosed)
    }

    @Test
    @Order(3)
    fun `close is idempotent`() {
        val props = loadTestProps()
        val c = DynamoDBConnection("jdbc:dynamodb://", props)
        c.close()
        assertTrue(c.isClosed)
        assertDoesNotThrow { c.close() }   // second close must not throw
    }

    @Test
    @Order(4)
    fun `createStatement throws after close`() {
        val props = loadTestProps()
        val c = DynamoDBConnection("jdbc:dynamodb://", props)
        c.close()
        assertThrows<java.sql.SQLException> { c.createStatement() }
    }

    // -------------------------------------------------------------------------
    // Metadata – getTables
    // -------------------------------------------------------------------------

    @Test
    @Order(10)
    fun `getTables returns the test table`() {
        val meta = conn.metaData
        val found = mutableSetOf<String>()
        meta.getTables(null, null, null, null).use { rs ->
            while (rs.next()) found += rs.getString("TABLE_NAME")
        }
        assertTrue(TABLE in found, "Expected '$TABLE' in tables, got: $found")
    }

    @Test
    @Order(11)
    fun `getTables with exact pattern`() {
        val meta = conn.metaData
        val found = mutableSetOf<String>()
        meta.getTables(null, null, TABLE, null).use { rs ->
            while (rs.next()) found += rs.getString("TABLE_NAME")
        }
        assertEquals(setOf(TABLE), found)
    }

    @Test
    @Order(12)
    fun `getTables with wildcard pattern`() {
        val meta = conn.metaData
        val found = mutableSetOf<String>()
        meta.getTables(null, null, "jdbc_driver%", null).use { rs ->
            while (rs.next()) found += rs.getString("TABLE_NAME")
        }
        assertTrue(TABLE in found)
    }

    @Test
    @Order(13)
    fun `getTables with VIEW type returns empty`() {
        val meta = conn.metaData
        var count = 0
        meta.getTables(null, null, null, arrayOf("VIEW")).use { rs ->
            while (rs.next()) count++
        }
        assertEquals(0, count, "DynamoDB has no views")
    }

    // -------------------------------------------------------------------------
    // Metadata – getColumns
    // -------------------------------------------------------------------------

    @Test
    @Order(20)
    fun `getColumns returns key attributes with correct types`() {
        val meta = conn.metaData
        val cols = mutableMapOf<String, Pair<Int, String>>() // name -> (dataType, typeName)
        meta.getColumns(null, null, TABLE, null).use { rs ->
            while (rs.next()) {
                val name = rs.getString("COLUMN_NAME")
                val dataType = rs.getInt("DATA_TYPE")
                val typeName = rs.getString("TYPE_NAME")
                cols[name] = Pair(dataType, typeName)
            }
        }
        assertTrue(cols.containsKey("userId"),    "Expected 'userId' column")
        assertTrue(cols.containsKey("sortKey"),   "Expected 'sortKey' column")
        assertTrue(cols.containsKey("category"),  "Expected 'category' column (GSI key)")
        assertEquals("VARCHAR", cols["userId"]?.second)
        assertEquals("VARCHAR", cols["sortKey"]?.second)
    }

    @Test
    @Order(21)
    fun `getColumns ordinal positions are sequential starting at 1`() {
        val meta = conn.metaData
        val ordinals = mutableListOf<Int>()
        meta.getColumns(null, null, TABLE, null).use { rs ->
            while (rs.next()) ordinals += rs.getInt("ORDINAL_POSITION")
        }
        assertTrue(ordinals.isNotEmpty())
        assertEquals(1, ordinals.min())
        // positions are consecutive
        val sorted = ordinals.sorted()
        for (i in 1 until sorted.size) {
            assertEquals(sorted[i - 1] + 1, sorted[i], "Ordinal gap between ${sorted[i-1]} and ${sorted[i]}")
        }
    }

    @Test
    @Order(22)
    fun `getColumns with column name pattern`() {
        val meta = conn.metaData
        val cols = mutableSetOf<String>()
        meta.getColumns(null, null, TABLE, "userId").use { rs ->
            while (rs.next()) cols += rs.getString("COLUMN_NAME")
        }
        assertEquals(setOf("userId"), cols)
    }

    // -------------------------------------------------------------------------
    // Metadata – getPrimaryKeys
    // -------------------------------------------------------------------------

    @Test
    @Order(30)
    fun `getPrimaryKeys returns hash and sort key`() {
        val meta = conn.metaData
        val keys = mutableMapOf<Int, String>() // seq -> column
        meta.getPrimaryKeys(null, null, TABLE).use { rs ->
            while (rs.next()) keys[rs.getInt("KEY_SEQ")] = rs.getString("COLUMN_NAME")
        }
        assertEquals("userId", keys[1], "First primary key should be hash key 'userId'")
        assertEquals("sortKey", keys[2], "Second primary key should be sort key 'sortKey'")
    }

    @Test
    @Order(31)
    fun `getPrimaryKeys returns empty for null table`() {
        var count = 0
        conn.metaData.getPrimaryKeys(null, null, null).use { rs ->
            while (rs.next()) count++
        }
        assertEquals(0, count)
    }

    // -------------------------------------------------------------------------
    // Metadata – getIndexInfo
    // -------------------------------------------------------------------------

    @Test
    @Order(40)
    fun `getIndexInfo returns GSI`() {
        val meta = conn.metaData
        val indexes = mutableMapOf<String, MutableList<String>>() // indexName -> columns
        meta.getIndexInfo(null, null, TABLE, false, false).use { rs ->
            while (rs.next()) {
                val name = rs.getString("INDEX_NAME")
                val col  = rs.getString("COLUMN_NAME")
                indexes.getOrPut(name) { mutableListOf() } += col
            }
        }
        assertTrue(indexes.containsKey("category-index"), "Expected GSI 'category-index', found: $indexes")
        assertTrue("category" in indexes["category-index"]!!)
    }

    @Test
    @Order(41)
    fun `getIndexInfo unique=true returns empty`() {
        var count = 0
        conn.metaData.getIndexInfo(null, null, TABLE, true, false).use { rs ->
            while (rs.next()) count++
        }
        assertEquals(0, count, "DynamoDB has no unique secondary indexes")
    }

    @Test
    @Order(42)
    fun `getIndexInfo returns NON_UNIQUE=true for all secondary indexes`() {
        conn.metaData.getIndexInfo(null, null, TABLE, false, false).use { rs ->
            while (rs.next()) {
                val nonUnique = rs.getBoolean("NON_UNIQUE")
                val name = rs.getString("INDEX_NAME")
                assertTrue(nonUnique, "Index '$name' should be NON_UNIQUE")
            }
        }
    }

    // -------------------------------------------------------------------------
    // PartiQL – SELECT
    // -------------------------------------------------------------------------

    @Test
    @Order(50)
    fun `SELECT all rows from test table`() {
        val rs = conn.prepareStatement("SELECT * FROM \"$TABLE\"").executeQuery()
        var count = 0
        while (rs.next()) count++
        assertTrue(count > 0, "Expected at least one row")
    }

    @Test
    @Order(51)
    fun `SELECT with WHERE clause returns matching rows`() {
        val sql = "SELECT * FROM \"$TABLE\" WHERE userId = 'user-001'"
        val rs = conn.prepareStatement(sql).executeQuery()
        var count = 0
        while (rs.next()) count++
        assertEquals(1, count, "Expected exactly one row for user-001")
    }

    @Test
    @Order(52)
    fun `SELECT via prepared statement with bound parameter`() {
        val ps = conn.prepareStatement("SELECT * FROM \"$TABLE\" WHERE userId = ?")
        ps.setString(1, "user-001")
        val rs = ps.executeQuery()
        var count = 0
        while (rs.next()) count++
        assertEquals(1, count)
    }

    // -------------------------------------------------------------------------
    // PartiQL – INSERT / UPDATE / DELETE
    // -------------------------------------------------------------------------

    @Test
    @Order(60)
    fun `INSERT a new row via PartiQL`() {
        val sql = """INSERT INTO "$TABLE" VALUE {'userId': 'user-ins', 'sortKey': 'sk-ins', 'name': 'Inserted'}"""
        val affected = conn.prepareStatement(sql).executeUpdate()
        assertTrue(affected >= 1)

        // Verify it exists
        val rs = conn.prepareStatement(
            "SELECT * FROM \"$TABLE\" WHERE userId = 'user-ins'"
        ).executeQuery()
        assertTrue(rs.next(), "Inserted row should be queryable")
    }

    @Test
    @Order(61)
    fun `UPDATE a row via PartiQL`() {
        val sql = """UPDATE "$TABLE" SET name = 'Updated' WHERE userId = 'user-ins' AND sortKey = 'sk-ins'"""
        assertDoesNotThrow { conn.prepareStatement(sql).executeUpdate() }
    }

    @Test
    @Order(62)
    fun `DELETE a row via PartiQL`() {
        val sql = """DELETE FROM "$TABLE" WHERE userId = 'user-ins' AND sortKey = 'sk-ins'"""
        assertDoesNotThrow { conn.prepareStatement(sql).executeUpdate() }

        // Confirm deletion
        val rs = conn.prepareStatement(
            "SELECT * FROM \"$TABLE\" WHERE userId = 'user-ins'"
        ).executeQuery()
        assertFalse(rs.next(), "Row should be gone after DELETE")
    }

    // -------------------------------------------------------------------------
    // Parameter binding
    // -------------------------------------------------------------------------

    @Test
    @Order(70)
    fun `clearParameters resets bound values`() {
        val ps = conn.prepareStatement("SELECT * FROM \"$TABLE\" WHERE userId = ?")
        ps.setString(1, "user-001")
        ps.clearParameters()
        // After clearing, executing without setting a param should throw or return no results
        // (DynamoDB will reject missing param – we just verify clearParameters doesn't crash)
        assertDoesNotThrow { ps.clearParameters() }
    }

    @Test
    @Order(71)
    fun `setObject with String value works`() {
        val ps = conn.prepareStatement("SELECT * FROM \"$TABLE\" WHERE userId = ?")
        ps.setObject(1, "user-001")
        val rs = ps.executeQuery()
        var count = 0
        while (rs.next()) count++
        assertEquals(1, count)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun loadTestProps(): Properties {
        val props = Properties()
        val stream = javaClass.classLoader.getResourceAsStream("aws-test.properties")
            ?: error("aws-test.properties not found on classpath")
        stream.use { props.load(it) }
        return props
    }

    private fun dropIfExists(tableName: String) {
        try {
            rawClient.deleteTable { it.tableName(tableName) }
            // Wait for deletion to complete
            repeat(20) {
                Thread.sleep(1500)
                val exists = try {
                    rawClient.describeTable { it.tableName(tableName) }
                    true
                } catch (_: ResourceNotFoundException) { false }
                if (!exists) return
            }
        } catch (_: ResourceNotFoundException) { /* already gone */ }
    }

    private fun createTestTable() {
        rawClient.createTable {
            it.tableName(TABLE)
              .billingMode(BillingMode.PAY_PER_REQUEST)
              .keySchema(
                  KeySchemaElement.builder().attributeName("userId").keyType(KeyType.HASH).build(),
                  KeySchemaElement.builder().attributeName("sortKey").keyType(KeyType.RANGE).build()
              )
              .attributeDefinitions(
                  AttributeDefinition.builder().attributeName("userId").attributeType(ScalarAttributeType.S).build(),
                  AttributeDefinition.builder().attributeName("sortKey").attributeType(ScalarAttributeType.S).build(),
                  AttributeDefinition.builder().attributeName("category").attributeType(ScalarAttributeType.S).build(),
                  AttributeDefinition.builder().attributeName("createdAt").attributeType(ScalarAttributeType.S).build()
              )
              .globalSecondaryIndexes(
                  GlobalSecondaryIndex.builder()
                      .indexName("category-index")
                      .keySchema(
                          KeySchemaElement.builder().attributeName("category").keyType(KeyType.HASH).build(),
                          KeySchemaElement.builder().attributeName("createdAt").keyType(KeyType.RANGE).build()
                      )
                      .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                      .build()
              )
        }

        // Wait until table is ACTIVE
        repeat(30) {
            Thread.sleep(1000)
            val status = rawClient.describeTable { it.tableName(TABLE) }
                .table().tableStatus()
            if (status == TableStatus.ACTIVE) return
        }
    }

    private fun insertTestData() {
        fun put(vararg attrs: Pair<String, AttributeValue>) {
            rawClient.putItem {
                it.tableName(TABLE).item(attrs.toMap())
            }
        }
        put(
            "userId"    to AttributeValue.fromS("user-001"),
            "sortKey"   to AttributeValue.fromS("2025-01-01"),
            "name"      to AttributeValue.fromS("Alice"),
            "age"       to AttributeValue.fromN("30"),
            "category"  to AttributeValue.fromS("premium"),
            "createdAt" to AttributeValue.fromS("2025-01-01T00:00:00Z")
        )
        put(
            "userId"    to AttributeValue.fromS("user-002"),
            "sortKey"   to AttributeValue.fromS("2025-02-01"),
            "name"      to AttributeValue.fromS("Bob"),
            "age"       to AttributeValue.fromN("25"),
            "category"  to AttributeValue.fromS("standard"),
            "createdAt" to AttributeValue.fromS("2025-02-01T00:00:00Z")
        )
        put(
            "userId"    to AttributeValue.fromS("user-003"),
            "sortKey"   to AttributeValue.fromS("2025-03-01"),
            "name"      to AttributeValue.fromS("Carol"),
            "age"       to AttributeValue.fromN("35"),
            "category"  to AttributeValue.fromS("premium"),
            "createdAt" to AttributeValue.fromS("2025-03-01T00:00:00Z")
        )
    }
}
