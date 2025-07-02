package pack.simple_sock5.auth

import io.netty.channel.Channel


interface Authenticator {
    suspend fun authenticate(channel: Channel, user: String, pass: String): Boolean
}
