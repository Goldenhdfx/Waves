package com.wavesplatform.http

import com.typesafe.config.ConfigFactory
import com.wavesplatform.RequestGen
import com.wavesplatform.http.ApiMarshallers._
import com.wavesplatform.settings.RestAPISettings
import com.wavesplatform.state2.diffs.TransactionDiffer.TransactionValidationError
import com.wavesplatform.utx.UtxPool
import io.netty.channel.group.ChannelGroup
import org.scalacheck.Gen.posNum
import org.scalacheck.{Gen => G}
import org.scalamock.scalatest.PathMockFactory
import org.scalatest.prop.PropertyChecks
import play.api.libs.json.Json._
import play.api.libs.json._
import scorex.api.http._
import scorex.api.http.leasing.LeaseBroadcastApiRoute
import scorex.transaction.ValidationError.GenericError
import scorex.transaction.Transaction


class LeaseBroadcastRouteSpec extends RouteSpec("/leasing/broadcast/") with RequestGen with PathMockFactory with PropertyChecks {
  private val settings = RestAPISettings.fromConfig(ConfigFactory.load())
  private val utx = stub[UtxPool]
  private val allChannels = stub[ChannelGroup]

  (utx.putIfNew _).when(*).onCall((t: Transaction) => Left(TransactionValidationError(GenericError("foo"), t))).anyNumberOfTimes()

  "returns StateCheckFiled" - {
    val route = LeaseBroadcastApiRoute(settings, utx, allChannels).route

    val vt = Table[String, G[_ <: Transaction], (JsValue) => JsValue](
      ("url", "generator", "transform"),
      ("lease", leaseGen, identity),
      ("cancel", leaseCancelGen, {
        case o: JsObject => o ++ Json.obj("txId" -> o.value("leaseId"))
        case other => other
      })
    )

    def posting(url: String, v: JsValue): RouteTestResult = Post(routePath(url), v) ~> route

    "when state validation fails" in {
      forAll(vt) { (url, gen, transform) =>
        forAll(gen) { (t: Transaction) =>
          posting(url, transform(t.json())) should produce(StateCheckFailed(t, "foo"))
        }
      }
    }
  }

  "returns appropriate error code when validation fails for" - {
    val route = LeaseBroadcastApiRoute(settings, utx, allChannels).route

    "lease transaction" in forAll(leaseReq) { lease =>
      def posting[A: Writes](v: A): RouteTestResult = Post(routePath("lease"), v) ~> route

      forAll(nonPositiveLong) { q => posting(lease.copy(amount = q)) should produce(NegativeAmount(s"$q of waves")) }
      forAll(invalidBase58) { pk => posting(lease.copy(senderPublicKey = pk)) should produce(InvalidAddress) }
      forAll(invalidBase58) { a => posting(lease.copy(recipient = a)) should produce(InvalidAddress) }
      forAll(nonPositiveLong) { fee => posting(lease.copy(fee = fee)) should produce(InsufficientFee) }
      forAll(posNum[Long]) { quantity => posting(lease.copy(amount = quantity, fee = Long.MaxValue)) should produce(OverflowError) }
    }

    "lease cancel transaction" in forAll(leaseCancelReq) { cancel =>
      def posting[A: Writes](v: A): RouteTestResult = Post(routePath("cancel"), v) ~> route

      forAll(invalidBase58) { pk => posting(cancel.copy(txId = pk)) should produce(CustomValidationError("invalid.leaseTx")) }
      forAll(invalidBase58) { pk => posting(cancel.copy(senderPublicKey = pk)) should produce(InvalidAddress) }
      forAll(nonPositiveLong) { fee => posting(cancel.copy(fee = fee)) should produce(InsufficientFee) }
    }
  }
}
