package com.example.wear.presentation.communication


import org.json.JSONArray
import org.json.JSONObject

enum class WireDataType {
    ACCELEROMETER,
    HEART_RATE
}

enum class WireSource {
    REAL,
    DEMO
}

data class WireSample(
    val sequence: Long,
    val timestampNanos: Long,
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val bpm: Double? = null
)

data class SensorBatch(
    val sessionId: String,
    val batchId: String,
    val dataType: WireDataType,
    val source: WireSource,
    val samples: List<WireSample>,
    val schemaVersion: Int = CommunicationProtocol.SCHEMA_VERSION
)

data class BatchAcknowledgement(val sessionId: String, val batchId: String)

object CommunicationProtocol {

    fun encodeAcknowledgement(ack: BatchAcknowledgement): ByteArray {
        validateId(ack.sessionId, "sessionId")
        validateId(ack.batchId, "batchId")
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("sessionId", ack.sessionId)
            put("batchId", ack.batchId)
        }.toString().toByteArray(Charsets.UTF_8)
    }

    fun decodeAcknowledgement(payload: ByteArray): Result<BatchAcknowledgement> = runCatching {
        require(payload.isNotEmpty() && payload.size <= 1024) { "Invalid acknowledgement size" }
        val json = JSONObject(payload.toString(Charsets.UTF_8))
        val version = json.get("schemaVersion")
        require(version is Int && version == SCHEMA_VERSION) { "Unsupported schema version" }
        val sessionId = requiredString(json, "sessionId")
        val batchId = requiredString(json, "batchId")
        validateId(sessionId, "sessionId")
        validateId(batchId, "batchId")
        BatchAcknowledgement(sessionId, batchId)
    }


    const val SCHEMA_VERSION = 1

    const val HELLO_PATH = "/session/hello"
    const val HELLO_ACK_PATH = "/session/hello_ack"
    const val SENSOR_BATCH_PATH = "/sensor/batch"
    const val SENSOR_ACK_PATH = "/sensor/ack"
    const val SESSION_COMMAND_PATH = "/session/command"
    const val SESSION_STATE_PATH = "/session/state"
    const val SOURCE_COMMAND_PATH = "/session/source"
    const val SESSION_QUERY_PATH = "/session/query"

    // These are application limits, not API limits.
    const val MAX_PAYLOAD_BYTES = 32 * 1024
    const val MAX_SAMPLES_PER_BATCH = 100

    private const val MAX_ID_LENGTH = 128

    fun encodeBatch(batch: SensorBatch): ByteArray {
        validateBatch(batch)

        val samplesJson = JSONArray()

        for (sample in batch.samples) {
            val sampleJson = JSONObject().apply {
                // Decimal strings preserve exact 64-bit integer values.
                put("sequence", sample.sequence.toString())
                put("timestampNanos", sample.timestampNanos.toString())

                when (batch.dataType) {
                    WireDataType.ACCELEROMETER -> {
                        put("x", requireNotNull(sample.x))
                        put("y", requireNotNull(sample.y))
                        put("z", requireNotNull(sample.z))
                    }

                    WireDataType.HEART_RATE -> {
                        put("bpm", requireNotNull(sample.bpm))
                    }
                }
            }

            samplesJson.put(sampleJson)
        }

        val json = JSONObject().apply {
            put("schemaVersion", batch.schemaVersion)
            put("sessionId", batch.sessionId)
            put("batchId", batch.batchId)
            put("dataType", batch.dataType.name)
            put("source", batch.source.name)
            put("samples", samplesJson)
        }

        val payload = json.toString().toByteArray(Charsets.UTF_8)

        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "Payload exceeds the application size limit"
        }

        return payload
    }

    fun decodeBatch(payload: ByteArray): Result<SensorBatch> {
        return runCatching {
            require(payload.isNotEmpty()) {
                "Payload is empty"
            }
            require(payload.size <= MAX_PAYLOAD_BYTES) {
                "Payload exceeds the application size limit"
            }

            val json = JSONObject(payload.toString(Charsets.UTF_8))
            val versionValue = json.get("schemaVersion")

            require(
                versionValue is Int &&
                        versionValue == SCHEMA_VERSION
            ) {
                "Unsupported schema version"
            }

            val dataType = WireDataType.valueOf(
                requiredString(json, "dataType")
            )
            val source = WireSource.valueOf(
                requiredString(json, "source")
            )
            val samplesJson = json.getJSONArray("samples")

            require(samplesJson.length() in 1..MAX_SAMPLES_PER_BATCH) {
                "Invalid sample count"
            }

            val samples = ArrayList<WireSample>(samplesJson.length())

            for (index in 0 until samplesJson.length()) {
                val sampleJson = samplesJson.getJSONObject(index)

                val sequence = requiredLongString(
                    sampleJson,
                    "sequence"
                )
                val timestamp = requiredLongString(
                    sampleJson,
                    "timestampNanos"
                )

                val sample = when (dataType) {
                    WireDataType.ACCELEROMETER -> {
                        require(!sampleJson.has("bpm")) {
                            "Accelerometer sample contains a heart-rate field"
                        }

                        WireSample(
                            sequence = sequence,
                            timestampNanos = timestamp,
                            x = requiredNumber(sampleJson, "x"),
                            y = requiredNumber(sampleJson, "y"),
                            z = requiredNumber(sampleJson, "z")
                        )
                    }

                    WireDataType.HEART_RATE -> {
                        require(
                            !sampleJson.has("x") &&
                                    !sampleJson.has("y") &&
                                    !sampleJson.has("z")
                        ) {
                            "Heart-rate sample contains acceleration fields"
                        }

                        WireSample(
                            sequence = sequence,
                            timestampNanos = timestamp,
                            bpm = requiredNumber(sampleJson, "bpm")
                        )
                    }
                }

                samples.add(sample)
            }

            val batch = SensorBatch(
                sessionId = requiredString(json, "sessionId"),
                batchId = requiredString(json, "batchId"),
                dataType = dataType,
                source = source,
                samples = samples,
                schemaVersion = SCHEMA_VERSION
            )

            validateBatch(batch)
            batch
        }
    }

    private fun validateBatch(batch: SensorBatch) {
        require(batch.schemaVersion == SCHEMA_VERSION) {
            "Unsupported schema version"
        }

        validateId(batch.sessionId, "sessionId")
        validateId(batch.batchId, "batchId")

        require(batch.samples.size in 1..MAX_SAMPLES_PER_BATCH) {
            "Invalid sample count"
        }

        for (sample in batch.samples) {
            require(sample.sequence > 0L) {
                "Sequence must be positive"
            }
            require(sample.timestampNanos >= 0L) {
                "Timestamp must not be negative"
            }

            when (batch.dataType) {
                WireDataType.ACCELEROMETER -> {
                    require(
                        sample.x?.isFinite() == true &&
                                sample.y?.isFinite() == true &&
                                sample.z?.isFinite() == true &&
                                sample.bpm == null
                    ) {
                        "Invalid accelerometer sample"
                    }
                }

                WireDataType.HEART_RATE -> {
                    require(
                        sample.bpm?.isFinite() == true &&
                                sample.x == null &&
                                sample.y == null &&
                                sample.z == null
                    ) {
                        "Invalid heart-rate sample"
                    }
                }
            }
        }

        // Samples inside a batch must be ordered.
        // Gaps are allowed and will be tracked by the receiver.
        for (index in 1 until batch.samples.size) {
            val previous = batch.samples[index - 1]
            val current = batch.samples[index]

            require(current.sequence > previous.sequence) {
                "Samples must have increasing sequence numbers"
            }
            require(current.timestampNanos > previous.timestampNanos) {
                "Samples must have increasing timestamps"
            }
        }
    }

    private fun validateId(value: String, name: String) {
        require(value.isNotBlank() && value.length <= MAX_ID_LENGTH) {
            "Invalid $name"
        }
    }

    private fun requiredString(
        json: JSONObject,
        key: String
    ): String {
        val value = json.get(key)

        require(value is String) {
            "$key must be a string"
        }

        return value
    }

    private fun requiredLongString(
        json: JSONObject,
        key: String
    ): Long {
        return requireNotNull(requiredString(json, key).toLongOrNull()) {
            "$key must contain a valid Long"
        }
    }

    private fun requiredNumber(
        json: JSONObject,
        key: String
    ): Double {
        val value = json.get(key)

        require(value is Number) {
            "$key must be numeric"
        }

        val number = value.toDouble()

        require(number.isFinite()) {
            "$key must be finite"
        }

        return number
    }
}