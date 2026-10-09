package com.localfirst.realtimetranslator.model

/** R1 permission result policy; real projection token consumption is implemented in R2. */
enum class ProjectionDecision { GRANTED, DENIED, INVALID }

fun projectionDecision(resultApproved: Boolean, hasTokenIntent: Boolean): ProjectionDecision =
    when {
        !resultApproved -> ProjectionDecision.DENIED
        !hasTokenIntent -> ProjectionDecision.INVALID
        else -> ProjectionDecision.GRANTED
}
