package kio.postegre.types

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

@OptIn(ExperimentalSerializationApi::class)
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@SerialInfo
annotation class PgArray(
    val dimension: Int = 1,
    val lengths: IntArray = [],
    val lowerBounds: IntArray = [1],
)
