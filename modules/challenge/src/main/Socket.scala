package lila.challenge

import akka.actor._
import play.api.libs.iteratee._
import play.api.libs.json._
import scala.concurrent.duration.Duration

import lila.hub.TimeBomb
import lila.socket.actorApi.{ Connected => _, _ }
import lila.socket.Socket.{ Uid, GetVersion, SocketVersion }
import lila.socket.{ SocketActor, History, Historical }

private final class Socket(
    challengeId: String,
    val history: History[Unit],
    getChallenge: Challenge.ID => Fu[Option[Challenge]],
    uidTimeout: Duration,
    socketTimeout: Duration
) extends SocketActor[Socket.Member](uidTimeout) with Historical[Socket.Member, Unit] {

  private val timeBomb = new TimeBomb(socketTimeout)

  def receiveSpecific = {

    case Socket.Reload =>
      getChallenge(challengeId) foreach {
        _ foreach { challenge =>
          notifyVersion("reload", JsNull, ())
        }
      }

    case Ping(uid, vOpt, lagCentis) =>
      ping(uid, lagCentis)
      timeBomb.delay
      pushEventsSinceForMobileBC(vOpt, uid)

    case Broom => {
      broom
      if (timeBomb.boom) self ! PoisonPill
    }

    case GetVersion => sender ! history.version

    case Socket.Join(uid, userId, owner, version) =>
      val (enumerator, channel) = Concurrent.broadcast[JsValue]
      val member = Socket.Member(channel, userId, owner)
      addMember(uid, member)
      sender ! Socket.Connected(
        prependEventsSince(version, enumerator, member),
        member
      )

    case Quit(uid) => quit(uid)
  }

  protected def shouldSkipMessageFor(message: Message, member: Socket.Member) = false
}

private object Socket {

  case class Member(
      channel: JsChannel,
      userId: Option[String],
      owner: Boolean
  ) extends lila.socket.SocketMember {
    val troll = false
  }

  case class Join(uid: Uid, userId: Option[String], owner: Boolean, version: Option[SocketVersion])
  case class Connected(enumerator: JsEnumerator, member: Member)

  case object Reload
}
