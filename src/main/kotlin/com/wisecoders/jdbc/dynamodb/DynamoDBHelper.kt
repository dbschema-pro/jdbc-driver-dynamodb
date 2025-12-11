package com.wisecoders.jdbc.dynamodb


import com.wisecoders.jdbc.dynamodb.DynamoDBHelper.unwrapValue
import software.amazon.awssdk.services.dynamodb.model.AttributeValue


fun Map<String, AttributeValue>.unwrap(): Map<String, Any?> {
    return mapValues { (_, value) -> unwrapValue(value) }
}

object DynamoDBHelper{
    fun unwrapValue(value: AttributeValue): Any? {
        return when {
            value.s() != null       -> value.s()
            value.n() != null       -> parseNumber(value.n())
            value.bool() != null    -> value.bool()
            value.hasM()            -> value.m().unwrap()
            value.hasL()            -> value.l().map { unwrapValue(it) }
            value.nul()             -> null
            value.hasSs()           -> value.ss()
            value.hasNs()           -> value.ns().map { parseNumber(it) }
            value.hasBs()           -> value.bs().map { it.asByteArray() }
            value.b() != null       -> value.b().asByteArray()
            else                    -> null // Unknown or unsupported type
        }
    }

    fun parseNumber(n: String): Number {
        return try {
            val d = n.toDouble()
            if (d % 1 == 0.0) d.toLong() else d
        } catch (e: NumberFormatException) {
            0
        }
    }


}