package com.example.wear.presentation

/** Invoked only by the debug source set's receiver while the sensor page is open. */
internal object DemoControl {
    var configure: ((String) -> String)? = null
    var intervalDemoEnabled = false
        private set
    var intervalStartedAtNanos: Long? = null

    fun configureScenario(name: String): String {
        // Reuse AUTO's existing paired fake-source selection without changing the Activity.
        val sourceScenario = if (name == "INTERVAL") "AUTO" else name
        val result = configure?.invoke(sourceScenario) ?: "Open watch SensorActivity first"
        if (result == "DEMO configured: $sourceScenario") {
            intervalDemoEnabled = name == "INTERVAL"
            intervalStartedAtNanos = null
            return "DEMO configured: $name"
        }
        return result
    }
    var heartRateFault: com.example.wear.presentation.data.SensorStatus? = null
    var accelerationFault: com.example.wear.presentation.data.SensorStatus? = null
}
