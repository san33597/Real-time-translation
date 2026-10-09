package com.localfirst.realtimetranslator.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectionFlowTest {
    @Test fun validConsentNeedsApprovedResultAndIntent() {
        assertEquals(ProjectionDecision.GRANTED, projectionDecision(true, true))
        assertEquals(ProjectionDecision.DENIED, projectionDecision(false, true))
        assertEquals(ProjectionDecision.INVALID, projectionDecision(true, false))
    }
}
