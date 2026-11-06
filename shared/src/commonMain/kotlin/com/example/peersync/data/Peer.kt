package com.example.peersync.data

import kotlinx.serialization.Serializable

/** A paired device. */
@Serializable
data class Peer(val id: String, val name: String) {
    val shortId: String get() = id.take(10)
}
