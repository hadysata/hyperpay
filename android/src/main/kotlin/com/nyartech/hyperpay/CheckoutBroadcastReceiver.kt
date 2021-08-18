package com.nyartech.hyperpay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.oppwa.mobile.connect.checkout.dialog.CheckoutActivity

class CheckoutBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent!!.action

        if (CheckoutActivity.ACTION_ON_BEFORE_SUBMIT == action) {
            val brand = intent!!.getStringExtra(CheckoutActivity.EXTRA_PAYMENT_BRAND)
            val checkoutID = intent!!.getStringExtra(CheckoutActivity.EXTRA_CHECKOUT_ID)

            /* This callback can be used to request a new checkout ID if selected payment brand requires
               some specific parameters or just send back the same checkout id to continue checkout process */
            Intent(
                context,
                CheckoutActivity::class.java
            )
                .setAction(CheckoutActivity.ACTION_ON_BEFORE_SUBMIT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(CheckoutActivity.EXTRA_CHECKOUT_ID, checkoutID)
                .putExtra(
                    CheckoutActivity.EXTRA_TRANSACTION_ABORTED,
                    false
                )

            context!!.startActivity(intent)
        }
    }
}