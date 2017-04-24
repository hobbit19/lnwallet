package com.lightning.wallet.lncloud

import com.lightning.wallet.ln._
import collection.JavaConverters._
import org.bitcoinj.wallet.listeners._
import com.lightning.wallet.lncloud.JavaSerializer._
import rx.lang.scala.{Subscription, Observable => Obs}
import fr.acinq.bitcoin.{BinaryData, OutPoint}
import org.bitcoinj.core.{Coin, Transaction}

import com.lightning.wallet.helper.RichCursor
import com.lightning.wallet.Utils.app
import fr.acinq.bitcoin.Crypto.sha256
import org.bitcoinj.wallet.Wallet
import scala.util.Try


object ChainWatcher {
  def watchTxDepthLocal(watchTxId: String) = Obs[Int] { obs =>
    val listener = new org.bitcoinj.core.listeners.TransactionConfidenceEventListener {
      def onTransactionConfidenceChanged(wallet: org.bitcoinj.wallet.Wallet, tx: Transaction) =
        if (tx.getHashAsString == watchTxId) obs onNext tx.getConfidence.getDepthInBlocks
    }

    app.kit.wallet addTransactionConfidenceEventListener listener
    Subscription(app.kit.wallet removeTransactionConfidenceEventListener listener)
  }

  def watchInputUsedLocal(fundPoint: OutPoint) = Obs[Transaction] { obs =>
    val usesInput = (_: Transaction).getInputs.asScala.map(_.getOutpoint) exists { point =>
      point.getIndex == fundPoint.index && point.getHash.toString == fundPoint.hash.toString
    }

    val listener = new WalletCoinsReceivedEventListener with WalletCoinsSentEventListener {
      def onCoinsSent(w: Wallet, tx: Transaction, prev: Coin, now: Coin) = if (usesInput apply tx) obs onNext tx
      def onCoinsReceived(w: Wallet, tx: Transaction, prev: Coin, now: Coin) = if (usesInput apply tx) obs onNext tx
    }

    app.kit.wallet addCoinsSentEventListener listener
    app.kit.wallet addCoinsReceivedEventListener listener

    Subscription {
      app.kit.wallet removeCoinsSentEventListener listener
      app.kit.wallet removeCoinsReceivedEventListener listener
    }
  }
}

object StorageWrap {
  def put(value: String, key: String) = app.db txWrap {
    app.db.change(sql = Storage.newSql, params = value, key)
    app.db.change(sql = Storage.updSql, params = value, key)
  }

  def get(key: String): Try[String] = {
    val cursor = app.db.select(Storage.selectSql, key)
    RichCursor(cursor).headTry(_ string Storage.value)
  }
}

object PaymentSpecWrap extends PaymentSpecBag { me =>
  def updateStatus(status: String, hash: BinaryData) = {
    app.db.change(PaymentSpecs.updStatusSql, status, hash.toString)
    app.getContentResolver.notifyChange(app.db sqlPath PaymentSpecs.table, null)
  }

  def getPaymentSpec(hash: BinaryData): Try[PaymentSpec] = {
    val basicCursor = app.db.select(PaymentSpecs.selectByHashSql, hash.toString)
    RichCursor(basicCursor).headTry(_ string PaymentSpecs.data) map deserialize[PaymentSpec]
  }

  def putPaymentSpec(spec: PaymentSpec) = app.db txWrap {
    val paymentHashString = spec.invoice.paymentHash.toString
    app.db.change(PaymentSpecs.newSql, serialize(spec), paymentHashString, spec.status, spec.stamp.toString)
    app.db.change(PaymentSpecs.newVirtualSql, s"${spec.invoice.message.orNull} $paymentHashString", paymentHashString)
    app.getContentResolver.notifyChange(app.db sqlPath PaymentSpecs.table, null)
  }

  def replacePaymentSpec(spec: PaymentSpec) = {
    val hash: String = spec.invoice.paymentHash.toString
    app.db.change(PaymentSpecs.updSql, serialize(spec), spec.status, spec.stamp.toString, hash)
    app.getContentResolver.notifyChange(app.db sqlPath PaymentSpecs.table, null)
  }

  def addPreimage(preimage: BinaryData) = sha256(preimage.data) match { case hash =>
    for (spec <- me getPaymentSpec hash) me replacePaymentSpec spec.copy(preimage = Some apply preimage)
  }
}