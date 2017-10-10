/*
 * This file is part of AckCord, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2017 Katrix
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package net.katsstuff.ackcord.http.websocket.voice

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.control.NonFatal

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Cancellable, Status}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Source, SourceQueueWithComplete}
import akka.util.ByteString
import io.circe
import io.circe.parser
import io.circe.syntax._
import net.katsstuff.ackcord.AudioAPIMessage
import net.katsstuff.ackcord.data.{Snowflake, UserId}
import net.katsstuff.ackcord.http.websocket.AbstractWsHandler
import net.katsstuff.ackcord.http.websocket.AbstractWsHandler.Data
import net.katsstuff.ackcord.http.websocket.voice.VoiceUDPHandler.{Disconnect, DoIPDiscovery, FoundIP, RTPHeader, StartConnection}

class VoiceWsHandler(
    address: String,
    serverId: Snowflake,
    userId: UserId,
    sessionId: String,
    token: String,
    sendTo: Option[ActorRef]
)(implicit mat: Materializer)
    extends AbstractWsHandler[VoiceMessage, ResumeData](s"wss://$address?v=3") {
  import AbstractWsHandler._
  import VoiceWsHandler._
  import VoiceWsProtocol._

  private implicit val system: ActorSystem = context.system
  import system.dispatcher

  def parseMessage: Flow[Message, Either[circe.Error, VoiceMessage[_]], NotUsed] = {
    Flow[Message]
      .collect {
        case t: TextMessage => t.textStream.fold("")(_ + _)
      }
      .flatMapConcat(identity)
      .log("Received payload")
      .map(parser.parse(_).flatMap(_.as[VoiceMessage[_]]))
  }

  val speakingMap = mutable.HashMap.empty[Int, UserId]

  onTransition {
    case Inactive -> Active => self ! SendIdentify
  }

  when(Active) {
    case Event(InitSink, _) =>
      sender() ! AckSink
      stay()
    case Event(CompletedSink, _) =>
      log.info("Websocket connection completed")
      self ! Logout
      stay()
    case Event(Status.Failure(e), _) =>
      log.error(e, "Connection interrupted")
      throw e
    case Event(Left(NonFatal(e)), _) => throw e
    case event @ Event(Right(_: VoiceMessage[_]), _) =>
      val res = handleWsMessages(event)
      sender() ! AckSink
      res
    case Event(SendIdentify, WithQueue(queue, _)) =>
      val identifyObject = IdentifyData(serverId, userId, sessionId, token)

      val payload = (Identify(identifyObject): VoiceMessage[IdentifyData]).asJson.noSpaces
      log.debug(s"Sending payload: $payload")
      queue.offer(TextMessage(payload))

      stay()
    case Event(SendSelectProtocol, WithHeartbeat(Some(IPData(localAddress, port)), _, _, _, queue, _, _, _)) =>
      val protocolObj = SelectProtocolData("udp", SelectProtocolConnectionData(localAddress, port, "xsalsa20_poly1305"))
      val payload     = (SelectProtocol(protocolObj): VoiceMessage[SelectProtocolData]).asJson.noSpaces
      log.debug(s"Sending payload: $payload")
      queue.offer(TextMessage(payload))
      stay()
    case Event(SendHeartbeat, data @ WithHeartbeat(_, _, receivedAck, _, queue, _, _, _)) =>
      if (receivedAck) {
        val nonce = System.currentTimeMillis().toInt

        val payload = (Heartbeat(nonce): VoiceMessage[Int]).asJson.noSpaces
        log.debug(s"Sending payload: $payload")
        queue.offer(TextMessage(payload))
        log.debug("Sent Heartbeat")

        stay using data.copy(receivedAck = false, previousNonce = Some(nonce))
      } else throw new AckException("Did not receive a Heartbeat ACK between heartbeats")
    case Event(FoundIP(localAddress, port), data: WithHeartbeat) =>
      self ! SendSelectProtocol
      stay using data.copy(ipData = Some(IPData(localAddress, port)))
    case Event(SetSpeaking(speaking), data: WithHeartbeat) =>
      val message = SpeakingData(speaking, 0, data.ssrc, None)
      val payload = (Speaking(message): VoiceMessage[SpeakingData]).asJson.noSpaces

      log.debug(s"Sending payload: $payload")
      data.queue.offer(TextMessage(payload))
      stay()
    case Event(ReceivedData(data, header), _) =>
      sendTo.foreach(_ ! AudioAPIMessage.ReceivedData(data, speakingMap.get(header.ssrc), header, serverId, userId))
      stay()
    case Event(setSource: SetDataSource, data: WithHeartbeat) =>
      self ! SetSpeaking(true)
      data.connectionActor.forward(setSource)
      stay()
    case Event(FinishedSource, _) =>
      self ! SetSpeaking(false)
      sendTo.foreach(_ ! AudioAPIMessage.FinishedSource(serverId, userId))
      stay()
    case Event(ConnectionDied, _) =>
      throw new IllegalStateException("Voice connection died") //TODO: Guard this behind condition
    case Event(Logout, data: WithHeartbeat) =>
      data.heartbeatCancelableOpt.foreach(_.cancel())
      data.queueOpt.foreach(_.complete())
      data.connectionActor ! Disconnect
      goto(Inactive) using WithResumeData(None)
    case Event(Restart(fresh, waitDur), data) =>
      data.heartbeatCancelableOpt.foreach(_.cancel())
      data.queueOpt.foreach(_.complete())
      system.scheduler.scheduleOnce(waitDur, self, Login)
      goto(Inactive) using WithResumeData(if (fresh) None else data.resumeOpt)
  }

  def handleWsMessages: StateFunction = {
    case Event(Right(Ready(ReadyObject(ssrc, port, _, _))), WithQueue(queue, _)) =>
      val connectionActor = context.actorOf(VoiceUDPHandler.props(address, ssrc, port, self))
      connectionActor ! DoIPDiscovery
      context.watchWith(connectionActor, ConnectionDied)
      stay using WithUDPActor(ssrc, connectionActor, queue, ResumeData(serverId, sessionId, token))
    case Event(Right(Hello(heartbeatInterval)), WithUDPActor(ssrc, connection, queue, resume)) =>
      val cancellable =
        system.scheduler.schedule(0.seconds, (heartbeatInterval * 0.75).toInt.millis, self, SendHeartbeat)
      stay using WithHeartbeat(
        ipData = None,
        heartbeatCancelable = cancellable,
        receivedAck = true,
        previousNonce = None,
        queue = queue,
        ssrc = ssrc,
        connectionActor = connection,
        resume = resume
      )
    case Event(Right(HeartbeatACK(nonce)), data: WithHeartbeat) =>
      log.debug("Received HeartbeatACK")
      if (data.previousNonce.contains(nonce)) {
        stay using data.copy(receivedAck = true)
      } else throw new AckException(s"Received unknown nonce $nonce for HeartbeatACK")
    case Event(Right(SessionDescription(SessionDescriptionData(_, secretKey))), data: WithHeartbeat) =>
      data.connectionActor ! StartConnection(secretKey)
      stay()
    case Event(Right(Speaking(SpeakingData(isSpeaking, delay, ssrc, Some(speakingUserId)))), _) =>
      speakingMap.put(ssrc, speakingUserId)
      sendTo.foreach(_ ! AudioAPIMessage.UserSpeaking(speakingUserId, isSpeaking, delay, serverId, userId))
      stay()
  }

  initialize()
}
object VoiceWsHandler {
  case object SendIdentify
  case object SendSelectProtocol

  private case object ConnectionDied

  case class SetSpeaking(speaking: Boolean)
  case class ReceivedData(data: ByteString, rtpHeader: RTPHeader)
  case class SetDataSource(source: Source[ByteString, NotUsed])
  case object FinishedSource

  case class WithUDPActor(
      ssrc: Int,
      connectionActor: ActorRef,
      queue: SourceQueueWithComplete[Message],
      resume: ResumeData
  ) extends Data[ResumeData] {
    def resumeOpt:                       Option[ResumeData]                       = Some(resume)
    override def queueOpt:               Option[SourceQueueWithComplete[Message]] = Some(queue)
    override def heartbeatCancelableOpt: Option[Cancellable]                      = None
  }

  case class IPData(address: String, port: Int)
  case class WithHeartbeat(
      ipData: Option[IPData],
      heartbeatCancelable: Cancellable,
      receivedAck: Boolean,
      previousNonce: Option[Int],
      queue: SourceQueueWithComplete[Message],
      ssrc: Int,
      connectionActor: ActorRef,
      resume: ResumeData
  ) extends Data[ResumeData] {
    def resumeOpt:                       Option[ResumeData]                       = Some(resume)
    override def queueOpt:               Option[SourceQueueWithComplete[Message]] = Some(queue)
    override def heartbeatCancelableOpt: Option[Cancellable]                      = Some(heartbeatCancelable)
  }
}