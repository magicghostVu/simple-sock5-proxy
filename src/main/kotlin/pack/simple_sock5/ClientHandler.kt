package pack.simple_sock5

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel

import pack.simple_sock5.auth.Authenticator

class ClientHandler(private val authenticator: Authenticator) : ChannelInitializer<SocketChannel>() {

    override fun initChannel(channel: SocketChannel) {
        val pipeline = channel.pipeline()
        pipeline.addLast(initConnectHandlerName, InitConnectProxyHandler(authenticator))
    }

    companion object {
         const val initConnectHandlerName = "init_connect_proxy"
         const val forwardingHandlerName = "forwarding_data"
    }
}