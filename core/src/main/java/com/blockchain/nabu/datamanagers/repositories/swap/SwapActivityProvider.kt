package com.blockchain.nabu.datamanagers.repositories.swap

import com.blockchain.nabu.Authenticator
import com.blockchain.nabu.datamanagers.CurrencyPair
import com.blockchain.nabu.datamanagers.TransferDirection
import com.blockchain.nabu.datamanagers.CustodialOrderState
import com.blockchain.nabu.datamanagers.custodialwalletimpl.LiveCustodialWalletManager.Companion.SUPPORTED_FUNDS_CURRENCIES
import com.blockchain.nabu.datamanagers.custodialwalletimpl.toCustodialOrderState
import com.blockchain.utils.fromIso8601ToUtc
import com.blockchain.utils.toLocalTime
import com.blockchain.nabu.service.NabuService
import info.blockchain.balance.AssetCatalogue
import info.blockchain.balance.FiatValue
import info.blockchain.balance.Money
import io.reactivex.rxjava3.core.Single
import java.math.BigDecimal

interface SwapActivityProvider {
    fun getSwapActivity(): Single<List<TradeTransactionItem>>
}

class SwapActivityProviderImpl(
    private val assetCatalogue: AssetCatalogue,
    private val authenticator: Authenticator,
    private val nabuService: NabuService
) : SwapActivityProvider {
    override fun getSwapActivity(): Single<List<TradeTransactionItem>> =
        authenticator.authenticate { sessionToken ->
            nabuService.fetchSwapActivity(sessionToken)
        }.map { response ->
            response.mapNotNull {
                val pair = CurrencyPair.fromRawPair(
                    it.pair, assetCatalogue, SUPPORTED_FUNDS_CURRENCIES
                ) ?: return@mapNotNull null

                val apiFiat = FiatValue.fromMinor(it.fiatCurrency, it.fiatValue.toLong())
                val receivingValue = pair.toDestinationMoney(it.priceFunnel.outputMoney.toBigInteger())
                // priceFunnel.price comes as Major Value
                val price = FiatValue.fromMajor(it.fiatCurrency, BigDecimal(it.priceFunnel.price))

                TradeTransactionItem(
                    txId = it.kind.depositTxHash ?: it.id,
                    timeStampMs = it.createdAt.fromIso8601ToUtc()?.toLocalTime()?.time
                        ?: throw java.lang.IllegalStateException("Missing timestamp or bad formatting"),
                    direction = it.kind.direction.mapToDirection(),
                    sendingAddress = it.kind.depositAddress,
                    receivingAddress = it.kind.withdrawalAddress,
                    state = it.state.toCustodialOrderState(),
                    sendingValue = pair.toSourceMoney(it.priceFunnel.inputMoney.toBigInteger()),
                    receivingValue = receivingValue,
                    withdrawalNetworkFee = pair.toDestinationMoney(it.priceFunnel.networkFee.toBigInteger()),
                    currencyPair = pair,
                    apiFiatValue = apiFiat,
                    price = price
                )
            }.filter {
                it.state.displayableState
            }
        }

    private fun String.mapToDirection(): TransferDirection =
        when (this) {
            "ON_CHAIN" -> TransferDirection.ON_CHAIN // from non-custodial to non-custodial
            "FROM_USERKEY" -> TransferDirection.FROM_USERKEY // from non-custodial to custodial
            "TO_USERKEY" -> TransferDirection.TO_USERKEY // from custodial to non-custodial - not in use currently
            "INTERNAL" -> TransferDirection.INTERNAL // from custodial to custodial
            else -> throw IllegalStateException("Unknown direction to map $this")
        }
}

data class TradeTransactionItem(
    val txId: String,
    val timeStampMs: Long,
    val direction: TransferDirection,
    val sendingAddress: String?,
    val receivingAddress: String?,
    val state: CustodialOrderState,
    val sendingValue: Money,
    val receivingValue: Money,
    val withdrawalNetworkFee: Money,
    val currencyPair: CurrencyPair,
    val apiFiatValue: FiatValue,
    val price: FiatValue
)
