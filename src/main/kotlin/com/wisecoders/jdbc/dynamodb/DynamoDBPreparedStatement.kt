package com.wisecoders.jdbc.dynamodb

import com.wisecoders.common_jdbc.jvm.result_set.ArrayResultSet
import com.wisecoders.common_jdbc.jvm.result_set.OkResultSet
import com.wisecoders.common_jdbc.jvm.sql.AbstractPreparedStatement
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.SQLException
import org.graalvm.polyglot.Context
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementRequest


class DynamoDBPreparedStatement(
    val connection: DynamoDBConnection,
    sql: String? = null,
) : AbstractPreparedStatement(sql) {


    override fun execute(sql: String): Boolean {
        rs = executeQuery(sql)
        return rs !is OkResultSet
    }

    override fun executeQuery(sql: String): ResultSet {
        if (dropTable(sql))
            return OkResultSet()
        if (usePartiQL(sql)) {
            val request = ExecuteStatementRequest.builder()
                .statement(sql)
                .build()

            try {
                val response = connection.clientInstance.executeStatement(request)
                rs = toArrayResultSet(unwrapResult(response.items()))
                return rs!!
            } catch (e: Exception) {
                throw SQLException("Failed to execute query: ${e.message}", e)
            }
        } else {
            val os = ByteArrayOutputStream()
            try {
                val res = executeJavaScript(sql, os)
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
            println("Execute partiql")
            val request = ExecuteStatementRequest.builder()
                .statement(sql)
                .build()

            return try {
                val response = connection.clientInstance.executeStatement(request)
                // DynamoDB doesn't give affected rows, so return 1 if OK
                if (response.hasItems()) response.items().size else 1
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
        return listOf<String>("select", "update", "insert", "delete").any {
            sql.startsWith(
                it.trimIndent(),
                ignoreCase = true
            )
        }
    }

    fun dropTable(sql: String): Boolean {
        val match = dropTableRegex.matchEntire(sql)
            ?: return false

        val tableName = match.groupValues[2]

        println("Dropping table: $tableName")

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
            .allowAllAccess(true) // allow interop with Java/Kotlin if needed
            .out(os)
            .build()

        val bindingsObject = ctx.getBindings("js")
        bindingsObject.putMember("client", connection.clientInstance)

        return ctx.eval("js", script)
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

    fun toArrayResultSet(items: List<Map<String, Any?>>): ArrayResultSet {
        val columnNames: List<String> = items.flatMap { it.keys }.distinct()
        val rows: List<MutableList<Any?>> = items.map { item ->
            columnNames.map { col -> item[col] }.toMutableList()
        }
        return ArrayResultSet(rows, columnNames)
    }

    companion object {
        val dropTableRegex = Regex(
            pattern = """(?i)^\s*DROP\s+TABLE\s+("?)([A-Za-z0-9_.-]+)\1\s*;?\s*$"""
        )
    }

}
