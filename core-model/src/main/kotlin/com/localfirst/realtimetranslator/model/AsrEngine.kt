package com.localfirst.realtimetranslator.model

interface AsrEngine : AutoCloseable {
    fun accept(frame: PcmFrame)
    fun resetAfterGap()
    fun finish()
    override fun close()
}
