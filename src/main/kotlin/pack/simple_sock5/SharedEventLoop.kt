package pack.simple_sock5

import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher

object SharedEventLoop {
    val eventLoopGroup = MultiThreadIoEventLoopGroup(
        Runtime.getRuntime().availableProcessors(),
        NioIoHandler.newFactory()
    )

    val shareScope = CoroutineScope(eventLoopGroup.asCoroutineDispatcher())
}