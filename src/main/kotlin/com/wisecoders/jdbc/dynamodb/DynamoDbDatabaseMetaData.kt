package com.wisecoders.jdbc.dynamodb

import com.wisecoders.common_jdbc.jvm.result_set.ArrayResultSet
import com.wisecoders.common_jdbc.jvm.sql.AbstractDatabaseMetaData
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.Types
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType

class DynamoDBDatabaseMetaData(
    private val client: DynamoDbClient,
    private val connection: Connection
) : AbstractDatabaseMetaData() {

    override fun getConnection(): Connection = connection

    /** Lists all table names, handling DynamoDB pagination transparently. */
    private fun listAllTableNames(): List<String> {
        val names = mutableListOf<String>()
        var lastEvaluated: String? = null
        do {
            val token = lastEvaluated
            val response = if (token == null) client.listTables()
                           else client.listTables { it.exclusiveStartTableName(token) }
            names.addAll(response.tableNames())
            lastEvaluated = response.lastEvaluatedTableName()
        } while (lastEvaluated != null)
        return names
    }

    /** Matches a name against a SQL LIKE pattern (% = any sequence, _ = any char). */
    private fun matchesPattern(name: String, pattern: String?): Boolean {
        if (pattern == null || pattern == "%") return true
        val regex = buildString {
            append("(?i)^")
            for (c in pattern) {
                when (c) {
                    '%' -> append(".*")
                    '_' -> append(".")
                    else -> append(Regex.escape(c.toString()))
                }
            }
            append("$")
        }
        return name.matches(Regex(regex))
    }

    override fun getTables(
        catalog: String?,
        schemaPattern: String?,
        tableNamePattern: String?,
        types: Array<out String>?
    ): ResultSet {
        val result = ArrayResultSet()
        result.setColumnNames(listOf(
            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS",
            "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION"
        ))
        // DynamoDB only has tables; skip if caller asks for views, synonyms, etc.
        if (types != null && "TABLE" !in types) return result

        for (tableName in listAllTableNames()) {
            if (!matchesPattern(tableName, tableNamePattern)) continue
            result.addRow(listOf(null, null, tableName, "TABLE", null, null, null, null, null, null))
        }
        return result
    }

    override fun getColumns(
        catalog: String?,
        schemaPattern: String?,
        tableNamePattern: String?,
        columnNamePattern: String?
    ): ResultSet {
        val result = ArrayResultSet()
        result.setColumnNames(listOf(
            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME",
            "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE",
            "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH",
            "ORDINAL_POSITION", "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE",
            "SOURCE_DATA_TYPE", "IS_AUTOINCREMENT"
        ))

        val tableNames = listAllTableNames().filter { matchesPattern(it, tableNamePattern) }

        for (tableName in tableNames) {
            try {
                val desc = client.describeTable { it.tableName(tableName) }.table()
                // LinkedHashMap preserves insertion order: key attributes are listed first
                val seen = linkedMapOf<String, Pair<Int, String>>()

                for (attrDef in desc.attributeDefinitions()) {
                    seen[attrDef.attributeName()] = attrTypeToJdbc(attrDef.attributeType())
                }

                // Sample items to discover additional non-key attributes (best-effort)
                val scan = client.scan { it.tableName(tableName).limit(50) }
                for (item in scan.items()) {
                    for ((attr, av) in item) {
                        if (!seen.containsKey(attr)) {
                            seen[attr] = avToJdbc(av)
                        }
                    }
                }

                val keyAttrs = desc.attributeDefinitions().map { it.attributeName() }.toSet()
                var ordinal = 1
                for ((colName, typeInfo) in seen) {
                    if (!matchesPattern(colName, columnNamePattern)) {
                        ordinal++
                        continue
                    }
                    val isKey = colName in keyAttrs
                    result.addRow(listOf(
                        null,                                                          // TABLE_CAT
                        null,                                                          // TABLE_SCHEM
                        tableName,                                                     // TABLE_NAME
                        colName,                                                       // COLUMN_NAME
                        typeInfo.first,                                                // DATA_TYPE
                        typeInfo.second,                                               // TYPE_NAME
                        0,                                                             // COLUMN_SIZE
                        null,                                                          // BUFFER_LENGTH
                        0,                                                             // DECIMAL_DIGITS
                        10,                                                            // NUM_PREC_RADIX
                        if (isKey) DatabaseMetaData.columnNoNulls else DatabaseMetaData.columnNullable, // NULLABLE
                        null,                                                          // REMARKS
                        null,                                                          // COLUMN_DEF
                        0,                                                             // SQL_DATA_TYPE
                        0,                                                             // SQL_DATETIME_SUB
                        0,                                                             // CHAR_OCTET_LENGTH
                        ordinal++,                                                     // ORDINAL_POSITION
                        if (isKey) "NO" else "YES",                                    // IS_NULLABLE
                        null,                                                          // SCOPE_CATALOG
                        null,                                                          // SCOPE_SCHEMA
                        null,                                                          // SCOPE_TABLE
                        null,                                                          // SOURCE_DATA_TYPE
                        "NO"                                                           // IS_AUTOINCREMENT
                    ))
                }
            } catch (_: ResourceNotFoundException) {
                // Table was removed between list and describe – skip silently
            }
        }
        return result
    }

    override fun getPrimaryKeys(
        catalog: String?,
        schema: String?,
        table: String?
    ): ResultSet {
        val result = ArrayResultSet()
        result.setColumnNames(listOf("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME"))
        if (table == null) return result
        try {
            val desc = client.describeTable { it.tableName(table) }.table()
            desc.keySchema()?.forEachIndexed { idx, key ->
                result.addRow(listOf(null, null, table, key.attributeName(), idx + 1, "PK_$table"))
            }
        } catch (_: ResourceNotFoundException) {
            // Table doesn't exist – return empty result
        }
        return result
    }

    override fun getIndexInfo(
        catalog: String?,
        schema: String?,
        table: String?,
        unique: Boolean,
        approximate: Boolean
    ): ResultSet {
        val result = ArrayResultSet()
        result.setColumnNames(listOf(
            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE",
            "INDEX_QUALIFIER", "INDEX_NAME", "TYPE", "ORDINAL_POSITION",
            "COLUMN_NAME", "ASC_OR_DESC", "CARDINALITY", "PAGES", "FILTER_CONDITION"
        ))
        if (table == null) return result
        // DynamoDB secondary indexes are never unique – return empty when unique=true is requested
        if (unique) return result

        try {
            val desc = client.describeTable { it.tableName(table) }.table()

            desc.globalSecondaryIndexes()?.forEach { gsi ->
                gsi.keySchema().forEachIndexed { ordinal, key ->
                    result.addRow(listOf(
                        null, null, table,
                        true,                                     // NON_UNIQUE
                        null,                                     // INDEX_QUALIFIER
                        gsi.indexName(),                          // INDEX_NAME
                        DatabaseMetaData.tableIndexOther,         // TYPE
                        ordinal + 1,                              // ORDINAL_POSITION
                        key.attributeName(),                      // COLUMN_NAME
                        if (key.keyType() == KeyType.RANGE) "A" else null, // ASC_OR_DESC (null for hash/partition key)
                        0,                                        // CARDINALITY
                        0,                                        // PAGES
                        null                                      // FILTER_CONDITION
                    ))
                }
            }

            desc.localSecondaryIndexes()?.forEach { lsi ->
                lsi.keySchema().forEachIndexed { ordinal, key ->
                    result.addRow(listOf(
                        null, null, table,
                        true,                                     // NON_UNIQUE
                        null,                                     // INDEX_QUALIFIER
                        lsi.indexName(),                          // INDEX_NAME
                        DatabaseMetaData.tableIndexOther,         // TYPE
                        ordinal + 1,                              // ORDINAL_POSITION
                        key.attributeName(),                      // COLUMN_NAME
                        if (key.keyType() == KeyType.RANGE) "A" else null, // ASC_OR_DESC
                        0,                                        // CARDINALITY
                        0,                                        // PAGES
                        null                                      // FILTER_CONDITION
                    ))
                }
            }
        } catch (_: ResourceNotFoundException) {
            // Table doesn't exist – return empty result
        }
        return result
    }

    override fun getExportedKeys(
        catalog: String?,
        schema: String?,
        table: String?
    ): ResultSet {
        val result = ArrayResultSet()
        result.setColumnNames(listOf(
            "PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME",
            "FKTABLE_CAT", "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME",
            "KEY_SEQ", "UPDATE_RULE", "DELETE_RULE", "FK_NAME", "PK_NAME", "DEFERRABILITY"
        ))
        return result
    }

    private fun attrTypeToJdbc(type: ScalarAttributeType?): Pair<Int, String> = when (type) {
        ScalarAttributeType.S -> Pair(Types.VARCHAR, "VARCHAR")
        ScalarAttributeType.N -> Pair(Types.NUMERIC, "NUMERIC")
        ScalarAttributeType.B -> Pair(Types.BINARY, "BINARY")
        else                  -> Pair(Types.OTHER, "OTHER")
    }

    private fun avToJdbc(av: AttributeValue): Pair<Int, String> = when {
        av.s() != null    -> Pair(Types.VARCHAR, "VARCHAR")
        av.n() != null    -> Pair(Types.NUMERIC, "NUMERIC")
        av.bool() != null -> Pair(Types.BOOLEAN, "BOOLEAN")
        av.hasL()         -> Pair(Types.ARRAY, "ARRAY")
        av.hasM()         -> Pair(Types.STRUCT, "MAP")
        av.hasSs()        -> Pair(Types.ARRAY, "STRING_SET")
        av.hasNs()        -> Pair(Types.ARRAY, "NUMBER_SET")
        av.hasBs()        -> Pair(Types.ARRAY, "BINARY_SET")
        av.b() != null    -> Pair(Types.BINARY, "BINARY")
        av.nul() == true  -> Pair(Types.NULL, "NULL")
        else              -> Pair(Types.OTHER, "OTHER")
    }

    override fun allProceduresAreCallable(): Boolean = false
    override fun allTablesAreSelectable(): Boolean = true
    override fun getURL(): String = (connection as DynamoDBConnection).endpointUrl
        .replace(Regex("^https?://"), "jdbc:dynamodb://")
    override fun getUserName(): String = ""
    override fun isReadOnly(): Boolean = false
    override fun nullsAreSortedHigh(): Boolean = false
    override fun nullsAreSortedLow(): Boolean = false
    override fun nullsAreSortedAtStart(): Boolean = false
    override fun nullsAreSortedAtEnd(): Boolean = false
    override fun getDatabaseProductName(): String = "DynamoDB"
    override fun getDatabaseProductVersion(): String = "1.0"
    override fun getDriverName(): String = "DynamoDB JDBC Driver"
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
