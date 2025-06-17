package pack.simple_sock5

import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler

object SharedEventLoop {
    val eventLoopGroup = MultiThreadIoEventLoopGroup(
        Runtime.getRuntime().availableProcessors(),
        NioIoHandler.newFactory()
    )
}