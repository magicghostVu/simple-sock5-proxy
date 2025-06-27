package pack.simple_sock5.forwarding

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.channel.SimpleChannelInboundHandler
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import pack.simple_sock5.DestinationDisconnected
import pack.simple_sock5.DestinationSocketActive
import pack.simple_sock5.ReceivedDataFromTarget

class DestinationSocketHandling(
    // maybe change cho weak ref
    val userClientPipeline: ChannelPipeline
) : SimpleChannelInboundHandler<ByteBuf>(true) {
    private val logger: Logger = LoggerFactory.getLogger("client-forwarding")
    override fun channelActive(ctx: ChannelHandlerContext) {
        userClientPipeline.fireUserEventTriggered(
            DestinationSocketActive(ctx.pipeline())
        )
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        userClientPipeline.fireUserEventTriggered(
            DestinationDisconnected
        )
    }

    //nhận data từ destination server
    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
        //logger.debug("received data from target host")
        val bufferToSendBack = msg.retainedDuplicate()
        userClientPipeline.fireUserEventTriggered(
            ReceivedDataFromTarget(bufferToSendBack)
        )
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {

    }
}