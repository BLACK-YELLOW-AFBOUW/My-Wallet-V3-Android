package piuk.blockchain.android.ui.transactionflow.flow.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import piuk.blockchain.android.R
import com.blockchain.coincore.TxConfirmation
import com.blockchain.coincore.TxConfirmationValue
import piuk.blockchain.android.databinding.ItemCheckoutSimpleInfoBinding
import piuk.blockchain.android.ui.adapters.AdapterDelegate
import piuk.blockchain.android.ui.transactionflow.flow.ConfirmationPropertyKey
import piuk.blockchain.android.ui.transactionflow.flow.TxConfirmReadOnlyMapperCheckout

class SimpleConfirmationCheckoutDelegate(private val mapper: TxConfirmReadOnlyMapperCheckout) :
    AdapterDelegate<TxConfirmationValue> {
    override fun isForViewType(items: List<TxConfirmationValue>, position: Int): Boolean {
        return items[position].confirmation == TxConfirmation.SIMPLE_READ_ONLY
    }

    override fun onCreateViewHolder(parent: ViewGroup): RecyclerView.ViewHolder =
        SimpleConfirmationCheckoutItemViewHolder(
            ItemCheckoutSimpleInfoBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            mapper
        )

    override fun onBindViewHolder(
        items: List<TxConfirmationValue>,
        position: Int,
        holder: RecyclerView.ViewHolder
    ) = (holder as SimpleConfirmationCheckoutItemViewHolder).bind(
        items[position]
    )
}

private class SimpleConfirmationCheckoutItemViewHolder(
    val binding: ItemCheckoutSimpleInfoBinding,
    val mapper: TxConfirmReadOnlyMapperCheckout
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(item: TxConfirmationValue) {
        mapper.map(item).let {
            with(binding) {
                simpleItemLabel.text = it[ConfirmationPropertyKey.LABEL] as String
                simpleItemTitle.text = it[ConfirmationPropertyKey.TITLE] as String

                it[ConfirmationPropertyKey.IS_IMPORTANT]?.let { isImportant ->
                    if (isImportant as Boolean) {
                        simpleItemLabel.setTextAppearance(R.style.Text_Semibold_16)
                        simpleItemTitle.setTextAppearance(R.style.Text_Semibold_16)
                    } else {
                        simpleItemLabel.setTextAppearance(R.style.Text_Standard_14)
                        simpleItemTitle.setTextAppearance(R.style.Text_Standard_14)
                    }
                }
            }
        }
    }
}
