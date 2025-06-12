package pack.simple_sock5


import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelPipeline

sealed class ForwardingCustomEvent {
}

data class DestinationSocketActive(val destinationPipeline: ChannelPipeline) : ForwardingCustomEvent()

data class ReceivedDataFromTarget(val buffer: ByteBuf) : ForwardingCustomEvent()

data object VerifyConnect: ForwardingCustomEvent()