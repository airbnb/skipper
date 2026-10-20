package com.example.adminjson

import com.fasterxml.jackson.annotation.JsonFilter

/**
 * Shaped like a Thrift union from Airbnb's codegen: the class carries `@JsonFilter("thriftUnionFieldFilter")`
 * and `getJsonName()` names the one field that is set.
 */
@JsonFilter("thriftUnionFieldFilter")
data class Payout(
    var amountMicros: Long? = null,
    var giftCardCode: String? = null,
) {
    fun getJsonName(): String = if (amountMicros != null) "amountMicros" else "giftCardCode"
}
