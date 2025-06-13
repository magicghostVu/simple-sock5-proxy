package pack.simple_sock5

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.socket.nio.NioSocketChannel
import org.slf4j.LoggerFactory
import pack.simple_sock5.forwarding.DestinationSocketHandling
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

class ForwardingDataHandler(
    val targetConnect: InetSocketAddress,
    val bufferFromPrevious: ByteBuf,
) : SimpleChannelInboundHandler<ByteBuf>(true) {
    private val logger = LoggerFactory.getLogger("forwarding-data")

    private var destinationPipeline: ChannelPipeline? = null


    override fun handlerAdded(ctx: ChannelHandlerContext) {

        logger.info("change mode to forward, previous buffer size is {}", bufferFromPrevious.readableBytes())

        val destinationBoostrap = Bootstrap()
        destinationBoostrap.group(SharedEventLoop.eventLoopGroup)
        destinationBoostrap.channel(NioSocketChannel::class.java)
        destinationBoostrap.handler(DestinationSocketHandling(ctx.pipeline()))
        destinationBoostrap.connect(targetConnect)

        // set timeout for connect to target
        ctx.executor().schedule(
            {
                // trigger event to check connect
                ctx.pipeline().fireUserEventTriggered(
                    VerifyConnect,
                )
            },
            10,
            TimeUnit.SECONDS
        )
    }


    // nhận data từ user-client
    // forward đến cho target
    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
        val targetPipeline = destinationPipeline
        if (targetPipeline != null) {
            targetPipeline.channel().writeAndFlush(msg.retainedDuplicate())
        } else {
            logger.warn("user client send data when proxy not connected to target host")
        }
    }


    // nhận một số event
    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        val userClientChannel = ctx.channel()
        if (evt is ForwardingCustomEvent) {
            if (userClientChannel.isActive) {
                when (evt) {
                    is ReceivedDataFromTarget -> {
                        val buffer = evt.buffer
                        userClientChannel.writeAndFlush(buffer)
                    }

                    is DestinationSocketActive -> {
                        destinationPipeline = evt.destinationPipeline
                        logger.info("destination socket activated")
                        // send lại data cho user client
                        sendConnectSuccess(userClientChannel)

                        val boundedAddressForDestination =
                            evt.destinationPipeline.channel().localAddress() as InetSocketAddress
                        logger.info("bounded address for {}", boundedAddressForDestination)
                        sendBoundedIp(
                            userClientChannel,
                            boundedAddressForDestination.address,
                            boundedAddressForDestination.port
                        )

                        if (bufferFromPrevious.isReadable) {
                            evt.destinationPipeline.channel().writeAndFlush(bufferFromPrevious)
                        }
                    }

                    VerifyConnect -> {
                        if (this.destinationPipeline == null) {
                            logger.error("can not connect to destination {}, close channel", targetConnect)
                            // close ...
                            ctx.channel().close()
                        }
                    }

                    DestinationDisconnected -> {
                        logger.info("destination socket disconnect, close use client channel")
                        userClientChannel.close()
                    }
                }
            } else {
                when (evt) {
                    is DestinationSocketActive,
                    VerifyConnect,
                        -> {
                    }

                    // impossible
                    DestinationDisconnected -> {
                        // do nothing
                    }

                    is ReceivedDataFromTarget -> {
                        val buffer = evt.buffer
                        buffer.release()
                    }
                }
            }
        } else {
            logger.warn("event type not supported {}", evt)
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        logger.info("user client from {} disconnected", ctx.channel().remoteAddress())
        // cho disconnect bên target
        destinationPipeline?.channel()?.close()
    }

    private fun sendConnectSuccess(userClientChannel: Channel) {
        val buffer = userClientChannel.alloc().buffer(3)
        buffer.writeByte(InitConnectProxyHandler.SOCKS_VERSION_5.toInt())
        buffer.writeByte(InitConnectProxyHandler.REP_SUCCEEDED.toInt())
        buffer.writeByte(0) // reserve
        userClientChannel.writeAndFlush(buffer)
    }

    private fun sendBoundedIp(userClientChannel: Channel, boundedAddress: InetAddress, localPort: Int) {
        val addType = when (boundedAddress) {
            is Inet4Address -> InitConnectProxyHandler.ATYP_IPV4
            is Inet6Address -> InitConnectProxyHandler.ATYP_IPV6
        }
        val buffer = userClientChannel.alloc().buffer(20)
        buffer.writeByte(addType.toInt())
        buffer.writeBytes(boundedAddress.address)
        buffer.writeShort(localPort)
        userClientChannel.writeAndFlush(buffer)
    }

}