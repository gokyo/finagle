package com.twitter.finagle.postgresql.transport

import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.twitter.finagle.postgresql.FrontendMessage.Bind
import com.twitter.finagle.postgresql.FrontendMessage.Describe
import com.twitter.finagle.postgresql.FrontendMessage.DescriptionTarget
import com.twitter.finagle.postgresql.FrontendMessage.Execute
import com.twitter.finagle.postgresql.FrontendMessage.Flush
import com.twitter.finagle.postgresql.FrontendMessage.Parse
import com.twitter.finagle.postgresql.FrontendMessage.PasswordMessage
import com.twitter.finagle.postgresql.FrontendMessage.Query
import com.twitter.finagle.postgresql.FrontendMessage.SslRequest
import com.twitter.finagle.postgresql.FrontendMessage.StartupMessage
import com.twitter.finagle.postgresql.FrontendMessage.Sync
import com.twitter.finagle.postgresql.FrontendMessage.Version
import com.twitter.finagle.postgresql.Types.Format
import com.twitter.finagle.postgresql.Types.Name
import com.twitter.finagle.postgresql.Types.Oid
import com.twitter.finagle.postgresql.Types.WireValue
import com.twitter.finagle.postgresql.FrontendMessage
import com.twitter.finagle.postgresql.FrontendMessage.Close
import com.twitter.finagle.postgresql.FrontendMessage.CopyDone
import com.twitter.finagle.postgresql.FrontendMessage.CopyFail
import com.twitter.finagle.postgresql.PropertiesSpec
import com.twitter.io.Buf
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalatest.wordspec.AnyWordSpec

class MessageEncoderSpec extends AnyWordSpec with PropertiesSpec {

  def mkBuf(capacity: Int = 32768)(f: ByteBuffer => ByteBuffer): Buf = {
    val bb = ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN)
    f(bb)
    bb.flip()
    Buf.ByteBuffer.Owned(bb)
  }
  def cstring(s: String) = s.getBytes("UTF8") :+ 0x00.toByte

  val genStartupMessage: Gen[StartupMessage] =
    for {
      major <- Arbitrary.arbitrary[Short]
      minor <- Arbitrary.arbitrary[Short]
      user <- genAsciiString.map(_.value)
      database <- Gen.option(genAsciiString).map(_.map(_.value))
      nbOptions <- Gen.chooseNum(0, 10)
      keys <- Gen.containerOfN[List, AsciiString](nbOptions, genAsciiString).map(_.map(_.value))
      values <- Gen.containerOfN[List, AsciiString](nbOptions, genAsciiString).map(_.map(_.value))
    } yield StartupMessage(
      version = Version(major, minor),
      user = user,
      database = database,
      replication = None,
      params = (keys zip values).toMap,
    )

  implicit val arbStartupMessage: Arbitrary[StartupMessage] = Arbitrary(genStartupMessage)

  val genPasswordMessage = genAsciiString.map(_.value).map(PasswordMessage)
  implicit val arbPasswordMessage: Arbitrary[PasswordMessage] = Arbitrary(genPasswordMessage)

  val genQuery = genAsciiString.map(_.value).map(Query)
  implicit val arbQueryMessage: Arbitrary[Query] = Arbitrary(genQuery)

  val genParse: Gen[Parse] =
    for {
      name <- Arbitrary.arbitrary[Name]
      statement <- genAsciiString.map(_.value)
      nbParams <- Gen.chooseNum(0, 32)
      paramTypes <- Gen.containerOfN[List, Oid](nbParams, arbOid.arbitrary)
    } yield Parse(
      name = name,
      statement = statement,
      dataTypes = paramTypes,
    )
  implicit lazy val arbParse: Arbitrary[Parse] = Arbitrary(genParse)

  val genBind: Gen[Bind] =
    for {
      portal <- Arbitrary.arbitrary[Name]
      statement <- Arbitrary.arbitrary[Name]
      nbValues <- Gen.chooseNum(0, 32)
      paramFormats <- Gen.containerOfN[List, Format](nbValues, arbFormat.arbitrary)
      values <- Gen.containerOfN[List, WireValue](nbValues, arbValue.arbitrary)
      nbResults <- Gen.chooseNum(0, 32)
      resultFormats <- Gen.containerOfN[List, Format](nbResults, arbFormat.arbitrary)
    } yield Bind(
      portal = portal,
      statement = statement,
      formats = paramFormats,
      values = values,
      resultFormats = resultFormats,
    )
  implicit lazy val arbBind: Arbitrary[Bind] = Arbitrary(genBind)

  val genDescribe: Gen[Describe] =
    for {
      portal <- Arbitrary.arbitrary[Name]
      target <- Gen.oneOf(DescriptionTarget.Portal, DescriptionTarget.PreparedStatement)
    } yield Describe(
      name = portal,
      target = target,
    )
  implicit lazy val arbDescribe: Arbitrary[Describe] = Arbitrary(genDescribe)

  val genExecute: Gen[Execute] =
    for {
      portal <- Arbitrary.arbitrary[Name]
      maxRows <- Arbitrary.arbitrary[Int]
    } yield Execute(
      portal = portal,
      maxRows = maxRows,
    )
  implicit lazy val arbExecute: Arbitrary[Execute] = Arbitrary(genExecute)

  val genClose: Gen[Close] =
    for {
      name <- Arbitrary.arbitrary[Name]
      target <- Arbitrary.arbitrary[DescriptionTarget]
    } yield Close(
      name = name,
      target = target,
    )
  implicit lazy val arbClose: Arbitrary[Close] = Arbitrary(genClose)

  implicit lazy val arbCopyFail: Arbitrary[CopyFail] = Arbitrary(
    genAsciiString.map(s => CopyFail(s.value)))

  def encodeFragment[M <: FrontendMessage: Arbitrary](
    enc: MessageEncoder[M]
  )(
    toPacket: M => Packet
  ) =
    "encode correctly" in prop { msg: M =>
      enc.toPacket(msg) must be(toPacket(msg))
    }

  "MessageEncoder" should {

    "SslRequest" should {
      "encode correctly" in {
        MessageEncoder.sslRequestEncoder.toPacket(SslRequest) must be(
          Packet(None, Buf.ByteArray(0x04, 0xd2.toByte, 0x16, 0x2f)))
      }
    }

    "StartupMessage" should encodeFragment(MessageEncoder.startupEncoder) { msg =>
      Packet(
        cmd = None,
        body = mkBuf() { bb =>
          bb.putShort(msg.version.major)
            .putShort(msg.version.minor)
            .put(cstring("user")).put(cstring(msg.user))
          msg.database.foreach { db =>
            bb.put(cstring("database")).put(cstring(db))
          }
          msg.params.foreach {
            case (key, value) =>
              bb.put(cstring(key)).put(cstring(value))
          }
          bb.put(0.toByte)
        }
      )
    }

    "PasswordMessage" should encodeFragment(MessageEncoder.passwordEncoder) { msg =>
      Packet(
        cmd = Some('p'),
        body = mkBuf()(bb => bb.put(cstring(msg.password)))
      )
    }

    "Query" should encodeFragment(MessageEncoder.queryEncoder) { msg =>
      Packet(
        cmd = Some('Q'),
        body = mkBuf()(bb => bb.put(cstring(msg.value)))
      )
    }

    "Sync" should {
      "encode correctly" in {
        MessageEncoder.syncEncoder.toPacket(Sync) must be(
          Packet(
            cmd = Some('S'),
            body = Buf.Empty
          ))
      }
    }

    "Flush" should {
      "encode correctly" in {
        MessageEncoder.flushEncoder.toPacket(Flush) must be(
          Packet(
            cmd = Some('H'),
            body = Buf.Empty
          ))
      }
    }

    "Parse" should encodeFragment(MessageEncoder.parseEncoder) { msg =>
      Packet(
        cmd = Some('P'),
        body = mkBuf() { bb =>
          msg.name match {
            case Name.Named(name) => bb.put(cstring(name))
            case Name.Unnamed => bb.put(cstring(""))
          }
          bb.put(cstring(msg.statement))
          bb.putShort(msg.dataTypes.length.toShort)
          msg.dataTypes.foreach { oid =>
            bb.putInt((oid.value & 0xffffffff).toInt)
          }
          bb
        }
      )
    }

    "Bind" should encodeFragment(MessageEncoder.bindEncoder) { msg =>
      Packet(
        cmd = Some('B'),
        body = mkBuf() { bb =>
          msg.portal match {
            case Name.Named(name) => bb.put(cstring(name))
            case Name.Unnamed => bb.put(cstring(""))
          }
          msg.statement match {
            case Name.Named(name) => bb.put(cstring(name))
            case Name.Unnamed => bb.put(cstring(""))
          }
          bb.putShort(msg.formats.length.toShort)
          msg.formats.foreach {
            case Format.Text => bb.putShort(0)
            case Format.Binary => bb.putShort(1)
          }
          bb.putShort(msg.values.length.toShort)
          msg.values.foreach {
            case WireValue.Null => bb.putInt(-1)
            case WireValue.Value(v) => bb.putInt(v.length).put(Buf.ByteArray.Owned.extract(v))
          }
          bb.putShort(msg.resultFormats.length.toShort)
          msg.resultFormats.foreach {
            case Format.Text => bb.putShort(0)
            case Format.Binary => bb.putShort(1)
          }
          bb
        }
      )
    }

    "Describe" should encodeFragment(MessageEncoder.describeEncoder) { msg =>
      Packet(
        cmd = Some('D'),
        body = mkBuf() { bb =>
          msg.target match {
            case DescriptionTarget.Portal => bb.put('P'.toByte)
            case DescriptionTarget.PreparedStatement => bb.put('S'.toByte)
          }
          msg.name match {
            case Name.Named(name) => bb.put(cstring(name))
            case Name.Unnamed => bb.put(cstring(""))
          }
          bb
        }
      )
    }

    "Execute" should encodeFragment(MessageEncoder.executeEncoder) { msg =>
      Packet(
        cmd = Some('E'),
        body = mkBuf() { bb =>
          msg.portal match {
            case Name.Named(name) => bb.put(cstring(name))
            case Name.Unnamed => bb.put(cstring(""))
          }
          bb.putInt(msg.maxRows)
        }
      )
    }

    "Close" should encodeFragment(MessageEncoder.closeEncoder) { msg =>
      Packet(
        cmd = Some('C'),
        body = mkBuf() { bb =>
          msg.target match {
            case DescriptionTarget.Portal => bb.put('P'.toByte)
            case DescriptionTarget.PreparedStatement => bb.put('S'.toByte)
          }
          msg.name match {
            case Name.Named(name) => bb.put(cstring(name))
            case Name.Unnamed => bb.put(cstring(""))
          }
          bb
        }
      )
    }

    "CopyData" should encodeFragment(MessageEncoder.copyDataEncoder) { msg =>
      Packet(
        cmd = Some('d'),
        body = mkBuf() { bb =>
          bb.put(Buf.ByteBuffer.Owned.extract(msg.bytes))
          bb
        }
      )
    }

    "CopyDone" should {
      "encode correctly" in {
        MessageEncoder.copyDoneEncoder.toPacket(CopyDone) must be(
          Packet(
            cmd = Some('c'),
            body = Buf.Empty
          ))
      }
    }

    "CopyFail" should encodeFragment(MessageEncoder.copyFailEncoder) { msg =>
      Packet(
        cmd = Some('f'),
        body = mkBuf() { bb =>
          bb.put(cstring(msg.msg))
          bb
        }
      )
    }
  }

}
