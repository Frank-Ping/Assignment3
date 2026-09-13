package com.example.wear.presentation

/** Invoked only by the debug source set's receiver while the sensor page is open. */
internal object DemoControl {
    var configure: ((String) -> String)? = null
}
