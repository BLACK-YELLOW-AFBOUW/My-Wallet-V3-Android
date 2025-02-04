package com.blockchain.banking

import com.blockchain.nabu.models.data.BankPartner
import com.blockchain.nabu.models.data.LinkedBank
import info.blockchain.balance.FiatValue
import java.io.Serializable

enum class BankTransferAction {
    LINK, PAY
}

interface BankPartnerCallbackProvider {
    fun callback(partner: BankPartner, action: BankTransferAction): String
}

data class BankPaymentApproval(
    val paymentId: String,
    val authorisationUrl: String,
    val linkedBank: LinkedBank,
    val orderValue: FiatValue
) : Serializable