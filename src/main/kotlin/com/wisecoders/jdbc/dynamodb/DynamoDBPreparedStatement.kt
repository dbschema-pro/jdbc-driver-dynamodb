package com.wisecoders.jdbc.dynamodb

import com.wisecoders.common_jdbc.jvm.result_set.ListOfObjectsAsResultSet
import com.wisecoders.common_jdbc.jvm.result_set.OkResultSet
import com.wisecoders.common_jdbc.jvm.sql.AbstractPreparedStatement
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.SQLException
import java.util.TreeMap
import org.graalvm.polyglot.Context
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementRequest


class DynamoDBPreparedStatement(
    val connection: DynamoDBConnection,
    sql: String? = null,
) : AbstractPreparedStatement(sql) {

    private val parameters = TreeMap<Int, AttributeValue?>()

    override fun execute(sql: String): Boolean {
        rs = executeQuery(sql)
        return rs !is OkResultSet
    }

    override fun executeQuery(sql: String): ResultSet {
        if (dropTable(sql))
            return OkResultSet()
        if (usePartiQL(sql)) {
            val params = buildParameters()
            val request = ExecuteStatementRequest.builder()
                .statement(sql)
                .apply { if (params.isNotEmpty()) parameters(params) }
                .build()
            try {
                val response = connection.clientInstance.executeStatement(request)
                rs = ListOfObjectsAsResultSet(unwrapResult(response.items()))
                return rs!!
            } catch (e: Exception) {
                throw SQLException("Failed to execute query: ${e.message}", e)
            }
        } else {
            val os = ByteArrayOutputStream()
            try {
                executeJavaScript(sql, os)
            } catch (ex: Throwable) {
                println(ex)
            }
            println(os)
            return OkResultSet()
        }
    }

    override fun executeUpdate(sql: String): Int {
        if (dropTable(sql))
            return 1

        if (usePartiQL(sql)) {
            val params = buildParameters()
            val request = ExecuteStatementRequest.builder()
                .statement(sql)
                .apply { if (params.isNotEmpty()) parameters(params) }
                .build()
            return try {
                val response = connection.clientInstance.executeStatement(request)
                // DynamoDB INSERT/UPDATE/DELETE return empty items; return 1 on success
                if (response.items().isNotEmpty()) response.items().size else 1
            } catch (e: Exception) {
                throw SQLException("Failed to execute update: ${e.message}", e)
            }
        } else {
            val output = ByteArrayOutputStream()
            executeJavaScript(sql, output)
            println(output)
            return 1
        }
    }

    fun usePartiQL(sql: String): Boolean {
        return listOf("select", "update", "insert", "delete").any {
            sql.startsWith(it.trimIndent(), ignoreCase = true)
        }
    }

    fun dropTable(sql: String): Boolean {
        val match = dropTableRegex.matchEntire(sql)
            ?: return false

        val tableName = match.groupValues[2]
        try {
            val request = DeleteTableRequest.builder()
                .tableName(tableName)
                .build()
            connection.clientInstance.deleteTable(request)
            return true
        } catch (e: Exception) {
            throw SQLException("Error dropping table $tableName: ${e.message}")
        }
    }

    fun executeJavaScript(
        script: String,
        os: OutputStream,
    ): Any? {
        val ctx = Context.newBuilder("js")
            .allowAllAccess(true)
            .out(os)
            .build()

        val bindingsObject = ctx.getBindings("js")
        bindingsObject.putMember("client", connection.clientInstance)

        return ctx.eval("js", script)
    }

    // -------------------------------------------------
    // Parameter binding – maps 1-based indices to AttributeValues.
    // Parameters persist across executions until clearParameters() is called.
    // -------------------------------------------------

    override fun clearParameters() {
        parameters.clear()
    }

    override fun setNull(parameterIndex: Int, sqlType: Int) {
        parameters[parameterIndex] = AttributeValue.fromNul(true)
    }

    override fun setBoolean(parameterIndex: Int, x: Boolean) {
        parameters[parameterIndex] = AttributeValue.fromBool(x)
    }

    override fun setByte(parameterIndex: Int, x: Byte) {
        parameters[parameterIndex] = AttributeValue.fromN(x.toString())
    }

    override fun setShort(parameterIndex: Int, x: Short) {
        parameters[parameterIndex] = AttributeValue.fromN(x.toString())
    }

    override fun setInt(parameterIndex: Int, x: Int) {
        parameters[parameterIndex] = AttributeValue.fromN(x.toString())
    }

    override fun setLong(parameterIndex: Int, x: Long) {
        parameters[parameterIndex] = AttributeValue.fromN(x.toString())
    }

    override fun setFloat(parameterIndex: Int, x: Float) {
        parameters[parameterIndex] = AttributeValue.fromN(x.toBigDecimal().toPlainString())
    }

    override fun setDouble(parameterIndex: Int, x: Double) {
        parameters[parameterIndex] = AttributeValue.fromN(x.toBigDecimal().toPlainString())
    }

    override fun setBigDecimal(parameterIndex: Int, x: BigDecimal) {
        parameters[parameterIndex] = AttributeValue.fromN(x.toPlainString())
    }

    override fun setString(parameterIndex: Int, x: String) {
        parameters[parameterIndex] = AttributeValue.fromS(x)
    }

    override fun setBytes(parameterIndex: Int, x: ByteArray) {
        parameters[parameterIndex] = AttributeValue.fromB(SdkBytes.fromByteArray(x))
    }

    override fun setObject(parameterIndex: Int, x: Any) {
        parameters[parameterIndex] = toAttributeValue(x)
    }

    /** Converts a Kotlin/Java value recursively to an AttributeValue. */
    private fun toAttributeValue(value: Any?): AttributeValue = when (value) {
        null                -> AttributeValue.fromNul(true)
        is AttributeValue   -> value
        is Boolean          -> AttributeValue.fromBool(value)
        is String           -> AttributeValue.fromS(value)
        is BigDecimal       -> AttributeValue.fromN(value.toPlainString())
        is Number           -> AttributeValue.fromN(value.toString())
        is ByteArray        -> AttributeValue.fromB(SdkBytes.fromByteArray(value))
        is Map<*, *>        -> AttributeValue.fromM(
            value.entries.associate { (k, v) -> k.toString() to toAttributeValue(v) }
        )
        is List<*>          -> AttributeValue.fromL(value.map { toAttributeValue(it) })
        is Set<*>           -> {
            val items = value.toList()
            when (items.firstOrNull()) {
                is String -> AttributeValue.fromSs(items.map { it.toString() })
                is Number -> AttributeValue.fromNs(items.map { it.toString() })
                else      -> AttributeValue.fromL(items.map { toAttributeValue(it) })
            }
        }
        else                -> AttributeValue.fromS(value.toString())
    }

    /**
     * Builds the ordered parameter list for the PartiQL statement.
     * Validates that all indices between 1 and max are populated.
     */
    private fun buildParameters(): List<AttributeValue?> {
        if (parameters.isEmpty()) return emptyList()
        val maxIndex = parameters.lastKey()
        for (i in 1..maxIndex) {
            if (!parameters.containsKey(i)) {
                throw SQLException("Missing parameter at index $i; parameters must be set contiguously from 1 to $maxIndex")
            }
        }
        return (1..maxIndex).map { parameters[it] ?: AttributeValue.fromNul(true) }
    }

    fun unwrapAttributeValue(av: AttributeValue): Any? {
        return when {
            av.s() != null      -> av.s()
            av.n() != null      -> BigDecimal(av.n())
            av.bool() != null   -> av.bool()
            av.hasL()           -> av.l().map { unwrapAttributeValue(it) }
            av.hasM()           -> av.m().mapValues { (_, v) -> unwrapAttributeValue(v) }
            av.hasSs()          -> av.ss().toSet()
            av.hasNs()          -> av.ns().map { BigDecimal(it) }.toSet()
            av.hasBs()          -> av.bs().map { it.asByteArray() }.toSet()
            av.nul() == true    -> null
            else -> error("Unsupported AttributeValue type: $av")
        }
    }

    fun unwrapItem(item: Map<String, AttributeValue>): Map<String, Any?> {
        return item.mapValues { (_, v) -> unwrapAttributeValue(v) }
    }

    fun unwrapResult(items: List<Map<String, AttributeValue>>): List<Map<String, Any?>> {
        return items.map { unwrapItem(it) }
    }

    companion object {
        val dropTableRegex = Regex(
            pattern = """(?i)^\s*DROP\s+TABLE\s+("?)([A-Za-z0-9_.-]+)\1\s*;?\s*$"""
        )
    }
}
