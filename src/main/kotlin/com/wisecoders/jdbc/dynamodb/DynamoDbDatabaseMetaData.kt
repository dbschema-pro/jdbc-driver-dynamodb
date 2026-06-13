package com.wisecoders.jdbc.dynamodb

import com.wisecoders.common_jdbc.jvm.sql.AbstractDatabaseMetaData
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType

class DynamoDBDatabaseMetaData(
    private val client: DynamoDbClient,
    private val connection: Connection
) : AbstractDatabaseMetaData() {

    override fun getConnection(): Connection = connection

    override fun getTables(
        catalog: String?,
        schemaPattern: String?,
        tableNamePattern: String?,
        types: Array<out String>?
    ): ResultSet {
        val tables = client.listTables().tableNames()
        val data: List<Array<Any?>> = tables
            .filter { tableNamePattern == null || it.contains(tableNamePattern) }
            .map { arrayOf<Any?>(null, null, it, "TABLE", null) }

        return com.wisecoders.common_jdbc.jvm.sql.SimpleResultSet(TABLE_INFO, data)
    }



    override fun getColumns(
        catalog: String?,
        schemaPattern: String?,
        tableNamePattern: String?,
        columnNamePattern: String?
    ): ResultSet {
        val results = mutableListOf<Array<Any?>>()
        val tableNames = client.listTables().tableNames()

        for (tableName in tableNames) {
            if (tableNamePattern != null && !tableName.contains(tableNamePattern)) continue

            val seen = mutableSetOf<String>()

            // Get key schema
            val desc = client.describeTable { it.tableName(tableName) }.table()
            desc.attributeDefinitions().forEachIndexed { i, key ->
                seen.add(key.attributeName())
                val jdbcType = when (key.attributeType()) {
                    ScalarAttributeType.S -> Types.VARCHAR
                    ScalarAttributeType.N -> Types.NUMERIC
                    ScalarAttributeType.B -> Types.BINARY
                    else -> Types.OTHER
                }
                val jdbcTypeName = when (key.attributeType()) {
                    ScalarAttributeType.S -> "VARCHAR"
                    ScalarAttributeType.N -> "NUMERIC"
                    ScalarAttributeType.B -> "BINARY"
                    else -> "OTHER"
                }

                results += arrayOf(
                    null, null, tableName, key.attributeName(), jdbcType, jdbcTypeName, 0, null,
                    0, null, "NO", null, null, null, i + 1, "YES"
                )
            }

            // Try to get additional attributes by sampling items
            val scan = client.scan { it.tableName(tableName).limit(50) }
            scan.items().forEach { item ->
                item.keys.filter { !seen.contains(it) }.forEachIndexed { i, attr ->
                    seen.add(attr)
                    results += arrayOf(
                        null, null, tableName, attr, Types.VARCHAR, "JSON", 0, null,
                        0, null, "YES", null, null, null, i + 1 + 100, "YES"
                    )
                }
            }
        }

        return com.wisecoders.common_jdbc.jvm.sql.SimpleResultSet(
            COLUMN_INFO,
            results
        )
    }


    override fun getPrimaryKeys(
        catalog: String?,
        schema: String?,
        table: String?,
    ): ResultSet {
        val desc = client.describeTable { it.tableName(table) }.table()
        val results = mutableListOf<Array<Any?>>()

        desc.keySchema()?.forEachIndexed { idx, pk ->
                results += arrayOf(
                    null, null, table, pk.attributeName(), idx + 1, "pk_${table}"
                )
        }

        return com.wisecoders.common_jdbc.jvm.sql.SimpleResultSet(
            PRIMARY_KEY_INFO, results
        )

    }

    override fun getIndexInfo(
        catalog: String?,
        schema: String?,
        table: String?,
        unique: Boolean,
        approximate: Boolean
    ): ResultSet {
        val desc = client.describeTable { it.tableName(table) }.table()
        val results = mutableListOf<Array<Any?>>()

        desc.globalSecondaryIndexes()?.forEach { gsi ->
            gsi.keySchema().forEachIndexed { keyIdx, key ->
                results += arrayOf(
                    null, null, table,
                    true,              // NON_UNIQUE — GSIs are non-unique
                    gsi.indexName(),   // INDEX_QUALIFIER — the GSI name
                    3,                 // TYPE — tableIndexOther
                    keyIdx + 1,        // ORDINAL_POSITION
                    key.attributeName(), // COLUMN_NAME
                    "ASC"              // ASC_OR_DESC
                )
            }
        }

        desc.localSecondaryIndexes()?.forEach { lsi ->
            lsi.keySchema().forEachIndexed { keyIdx, key ->
                results += arrayOf(
                    null, null, table,
                    false,             // NON_UNIQUE — LSIs are unique within the partition
                    lsi.indexName(),   // INDEX_QUALIFIER — the LSI name
                    3,                 // TYPE — tableIndexOther
                    keyIdx + 1,        // ORDINAL_POSITION
                    key.attributeName(), // COLUMN_NAME
                    "ASC"              // ASC_OR_DESC
                )
            }
        }

        return com.wisecoders.common_jdbc.jvm.sql.SimpleResultSet(
            INDEX_INFO, results
        )
    }

    override fun getExportedKeys(
        catalog: String?,
        schema: String?,
        table: String?
    ): ResultSet {
        return com.wisecoders.common_jdbc.jvm.sql.SimpleResultSet(
            EXPORTED_KEYS_INFO,
            emptyList()
        )
    }

    // -------------------------------
    // Stub methods required by interface
    // -------------------------------
    override fun allProceduresAreCallable(): Boolean = false
    override fun allTablesAreSelectable(): Boolean = true
    override fun getURL(): String = "jdbc:dynamodb://"
    override fun getUserName(): String = ""
    override fun isReadOnly(): Boolean = false
    override fun nullsAreSortedHigh(): Boolean = false
    override fun nullsAreSortedLow(): Boolean = false
    override fun nullsAreSortedAtStart(): Boolean = false
    override fun nullsAreSortedAtEnd(): Boolean = false
    override fun getDatabaseProductName(): String = "DynamoDB"
    override fun getDatabaseProductVersion(): String = "1.0"
    override fun getDriverName(): String = "CustomDynamoDBDriver"
    override fun getDriverVersion(): String = "1.0"
    override fun getDriverMajorVersion(): Int = 1
    override fun getDriverMinorVersion(): Int = 0
    override fun usesLocalFiles(): Boolean = false
    override fun usesLocalFilePerTable(): Boolean = false
    override fun supportsMixedCaseIdentifiers(): Boolean = false
    override fun storesUpperCaseIdentifiers(): Boolean = false
    override fun storesLowerCaseIdentifiers(): Boolean = true
    override fun storesMixedCaseIdentifiers(): Boolean = false
    override fun supportsMixedCaseQuotedIdentifiers(): Boolean = false
    override fun storesUpperCaseQuotedIdentifiers(): Boolean = false
    override fun storesLowerCaseQuotedIdentifiers(): Boolean = true
    override fun storesMixedCaseQuotedIdentifiers(): Boolean = false


}
