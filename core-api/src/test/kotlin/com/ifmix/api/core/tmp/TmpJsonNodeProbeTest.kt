package com.ifmix.core.api.tmp

import org.junit.jupiter.api.Test

class TmpJsonNodeProbeTest {

    @Test
    fun probeV3Node() {
        try {
            val codec = org.babyfish.jimmer.jackson.codec.JsonCodec.jsonCodec()
            val reader = codec.readerFor(tools.jackson.databind.JsonNode::class.java)
            val v: Any = reader.read("{\"a\":1}")
            println("PROBE V3NODE OK -> $v (${v.javaClass.name})")
        } catch (e: Throwable) {
            println("PROBE V3NODE FAIL -> ${e.javaClass.name}: ${e.message}")
        }
    }

    @Test
    fun probeMap() {
        try {
            val codec = org.babyfish.jimmer.jackson.codec.JsonCodec.jsonCodec()
            val reader = codec.readerFor(Map::class.java)
            val v: Any = reader.read("{\"a\":1}")
            println("PROBE MAP OK -> $v (${v.javaClass.name})")
        } catch (e: Throwable) {
            println("PROBE MAP FAIL -> ${e.javaClass.name}: ${e.message}")
        }
    }

    @Test
    fun probeNodeToString() {
        val node = tools.jackson.databind.json.JsonMapper.builder().build().readTree("{\"a\":1,\"b\":\"x\"}")
        println("PROBE TOSTRING -> " + node.toString())
    }
}
