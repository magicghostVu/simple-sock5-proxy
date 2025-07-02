package pack.simple_sock5

import io.netty.util.AttributeKey

data class SpeedLimit(val readLimit: Long, val writeLimit: Long)

interface SpeedLimitService {
    suspend fun getSpeedLimit(username: String): SpeedLimit
}

val SPEED_LIMIT_KEY: AttributeKey<SpeedLimit> = AttributeKey.valueOf("speedLimit")
val USERNAME_KEY: AttributeKey<String> = AttributeKey.valueOf("username")
