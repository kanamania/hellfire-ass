package com.hellfire.ass.ai

import ai.onnxruntime.OrtEnvironment

/** Shared runtime privacy configuration; model/session ownership stays with the existing adapters. */
internal val onnxEnvironment: OrtEnvironment by lazy {
    OrtEnvironment.getEnvironment().apply { setTelemetry(false) }
}
