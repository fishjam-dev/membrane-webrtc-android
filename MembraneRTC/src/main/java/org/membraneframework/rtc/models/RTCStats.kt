package org.membraneframework.rtc.models

import java.math.BigInteger

data class QualityLimitationDurations(
    val bandwidth: Double,
    val cpu: Double,
    val none: Double,
    val other: Double
)

data class RTCOutboundStats(
    val kind: String? = "",
    val rid: String? = "",
    val bytesSent: BigInteger? = BigInteger("0"),
    val targetBitrate: Double? = 0.0,
    val packetsSent: Long? = 0,
    val framesEncoded: Long? = 0,
    val framesPerSecond: Double? = 0.0,
    val frameWidthHeightRatio: Double? = 0.0,
    val qualityLimitationDurations: QualityLimitationDurations?
)

data class RTCInboundStats(
    val kind: String? = "",
    val jitter: Double? = 0.0,
    val packetsLost: Int? = 0,
    val packetsReceived: Long? = 0,
    val bytesReceived: BigInteger? = BigInteger("0"),
    val framesReceived: Int? = 0,
    val frameWidthHeightRatio: Double? = 0.0,
    val framesPerSecond: Double? = 0.0,
    val framesDropped: Long? = 0
)

//
// {ssrc=270843940, kind=video, trackId=RTCMediaStreamTrack_receiver_3, transportId=RTCTransport_0_1,
//    codecId=RTCCodec_2_Inbound_100, mediaType=video, jitter=0.009, packetsLost=0, packetsReceived=588,
//    bytesReceived=630017, headerBytesReceived=11760, lastPacketReceivedTimestamp=1.682593173862E12,
//    jitterBufferDelay=6.619, jitterBufferEmittedCount=80, framesReceived=85, frameWidth=1280, frameHeight=720,
//    framesPerSecond=26.0, framesDecoded=57, keyFramesDecoded=1, framesDropped=24, totalDecodeTime=1.815,
//    totalProcessingDelay=3.645, totalAssemblyTime=0.094, framesAssembledFromMultiplePackets=57,
//    totalInterFrameDelay=3.0829999999999997, totalSquaredInterFrameDelay=0.238739,
//    decoderImplementation=c2.qti.avc.decoder, firCount=0, pliCount=0, nackCount=0, qpSum=1438, minPlayoutDelay=0.0}
