package pack.simple_sock5

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel

class ClientHandler : ChannelInitializer<SocketChannel>() {

    override fun initChannel(channel: SocketChannel) {
        val pipeline = channel.pipeline()
        pipeline.addLast(initConnectHandlerName, InitConnectProxyHandler())
    }

    companion object {
         const val initConnectHandlerName = "init_connect_proxy"
         const val forwardingHandlerName = "forwarding_data"
    }
}