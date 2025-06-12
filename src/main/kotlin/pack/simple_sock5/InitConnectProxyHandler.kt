package pack.simple_sock5

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.socket.SocketChannel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress


// handler này sẽ bị thay thế
//  sau khi init connect thành công
//  và chuyển sang chế độ streaming với target server
class InitConnectProxyHandler : SimpleChannelInboundHandler<ByteBuf>(true) {

    private val logger: Logger = LoggerFactory.getLogger("init-connect")

    private lateinit var stateInitConnect: InitConnectState

    override fun channelActive(ctx: ChannelHandlerContext) {
        logger.info("new channel active {}", ctx.channel().remoteAddress())
        stateInitConnect = ReadMethods(ctx.channel() as SocketChannel)
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
        // move buffer to heap

        logger.debug("received {} byte", msg.readableBytes())
        val copied = Unpooled.buffer(msg.readableBytes())
        msg.readBytes(copied)


        stateInitConnect = stateInitConnect.acceptBuffer(copied)

        val s = stateInitConnect
        when (s) {
            is ReadDestinationHost,
            is ReadMethods,
            is VerifyConnectCommand -> {
                logger.debug("not done init connect, continue")
            }

            is ReadDestinationPort -> {
                if (s.targetPort > 0) {
                    val targetConnect = InetSocketAddress(s.hostName, s.targetPort)
                    logger.info("done init connect, init new client to forward data with target {}", targetConnect)
                    ctx.pipeline().replace(
                        ClientHandler.initConnectHandlerName,
                        ClientHandler.forwardingHandlerName,
                        ForwardingDataHandler(targetConnect, s.buffer)
                    )
                }
            }
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        logger.info("new channel inactive {}", ctx.channel().remoteAddress())
    }

    companion object {
        const val SOCKS_VERSION_5: Byte = 0x05
        const val METHOD_NO_AUTHENTICATION_REQUIRED: Byte = 0x00
        const val CMD_CONNECT: Byte = 0x01
        const val ATYP_IPV4: Byte = 0x01
        const val ATYP_DOMAIN_NAME: Byte = 0x03


        // We'll parse but might not fully handle IPv6 connection if system doesn't support well
        const val ATYP_IPV6: Byte = 0x04

        const val REP_SUCCEEDED: Byte = 0x00
        const val REP_GENERAL_SOCKS_SERVER_FAILURE: Byte = 0x01
        const val REP_CONNECTION_NOT_ALLOWED_BY_RULESET: Byte = 0x02
        const val REP_NETWORK_UNREACHABLE: Byte = 0x03
        const val REP_HOST_UNREACHABLE: Byte = 0x04
        const val REP_CONNECTION_REFUSED: Byte = 0x05
        const val REP_COMMAND_NOT_SUPPORTED: Byte = 0x07
        const val REP_ADDRESS_TYPE_NOT_SUPPORTED: Byte = 0x08
    }
}