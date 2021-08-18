package com.nyartech.hyperpay

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.NonNull
import com.oppwa.mobile.connect.exception.PaymentError
import com.oppwa.mobile.connect.exception.PaymentException
import com.oppwa.mobile.connect.payment.BrandsValidation
import com.oppwa.mobile.connect.payment.CheckoutInfo
import com.oppwa.mobile.connect.payment.ImagesRequest
import com.oppwa.mobile.connect.payment.PaymentParams
import com.oppwa.mobile.connect.payment.card.CardPaymentParams
import com.oppwa.mobile.connect.provider.Connect
import com.oppwa.mobile.connect.provider.ITransactionListener
import com.oppwa.mobile.connect.provider.OppPaymentProvider
import com.oppwa.mobile.connect.provider.Transaction
import com.oppwa.mobile.connect.provider.TransactionType
import com.oppwa.mobile.connect.service.ConnectService
import com.oppwa.mobile.connect.service.IProviderBinder
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/** HyperpayPlugin */
class HyperpayPlugin : FlutterActivity(), FlutterPlugin, MethodCallHandler,
    ITransactionListener, MethodChannel.Result, ActivityAware {
    private val TAG = "HyperpayPlugin"

    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel

    // private lateinit var mContext: Context
    private lateinit var mApplicationContext: Context
    private lateinit var mActivity: Activity

    private var checkoutID = ""
    private var provider: OppPaymentProvider =
        OppPaymentProvider(context, Connect.ProviderMode.TEST)
    private var mode = provider.providerMode.name
    private var brand = Brand.UNKNOWN
    private var cardHolder: String = ""
    private var cardNumber: String = ""
    private var expiryMonth: String = ""
    private var expiryYear: String = ""
    private var cvv: String = ""

    private var transaction: Transaction? = null
    private var paymentResult: MethodChannel.Result? = null
    private var providerBinder: IProviderBinder? = null
    private var shopperResultURL: String? = ""
    private var Result: MethodChannel.Result? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        onAttachedToEngine(
            flutterPluginBinding.applicationContext,
            flutterPluginBinding.binaryMessenger
        )
    }

    private fun onAttachedToEngine(applicationContext: Context, messenger: BinaryMessenger) {
        this.mApplicationContext = applicationContext
        channel = MethodChannel(messenger, "hyperpay");
        channel.setMethodCallHandler(this);
    }

    // Handling result options
    private val handler: Handler = Handler(Looper.getMainLooper())
    override fun success(result: Any?) {
        handler.post { Result!!.success(result) }
    }

    override fun error(errorCode: String?, errorMessage: String?, errorDetails: Any?) {
        handler.post { Result!!.error(errorCode, errorMessage, errorDetails) }
    }

    override fun notImplemented() {
        handler.post { Result!!.notImplemented() }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d("Banana", "service is connected")

            providerBinder = service as IProviderBinder
            providerBinder!!.addTransactionListener(this@HyperpayPlugin)
            try {
                if (mode.equals("LIVE")) {
                    providerBinder!!.initializeProvider(Connect.ProviderMode.LIVE)
                } else {
                    providerBinder!!.initializeProvider(Connect.ProviderMode.TEST)
                }
            } catch (e: PaymentException) {
                paymentResult?.error(
                    "0.3",
                    e.localizedMessage,
                    ""
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            providerBinder = null
        }
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        if (call.method == "hyperpay") {
            Result = result
            val args: Map<String, Any> = call.arguments as Map<String, Any>
            checkoutID = (args["checkoutID"] as String?)!!
            mode = (args["mode"] as String?)!!
            brand = Brand.valueOf(args["brand"].toString())

            val card: Map<String, Any> = args["card"] as Map<String, Any>
            cardHolder = (card["holder"] as String?)!!
            cardNumber = (card["number"] as String?)!!
            expiryMonth = (card["expiryMonth"] as String?)!!
            expiryYear = (card["expiryYear"] as String?)!!
            cvv = (card["cvv"] as String?)!!

            if (mode == "LIVE") {
                provider.providerMode = Connect.ProviderMode.LIVE
            }

            try {
                val intent = Intent(mApplicationContext, ConnectService::class.java)
                startService(intent)
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }


            when (brand) {
                // If the brand is not provided it returns an error result
                Brand.UNKNOWN -> result.error(
                    "0.1",
                    "Please provide a valid brand",
                    ""
                )
                else -> {
                    checkCreditCardValid()

                    try {
                        val paymentParams: PaymentParams = CardPaymentParams(
                            checkoutID,
                            brand.name,
                            cardNumber,
                            cardHolder,
                            expiryMonth,
                            expiryYear,
                            cvv
                        )

                        Log.d("Banana", paymentParams.checkoutId + paymentParams.paymentBrand)
                        // Set shopper result URL
                        paymentParams.shopperResultUrl =
                            "${mActivity.packageName}://result"

                        Log.d("Banana", "${paymentParams.shopperResultUrl}")

                        val transaction = Transaction(paymentParams)

                        Log.d("Banana", "${transaction.redirectUrl}")

                        // TODO: Initialize binder

                        providerBinder?.submitTransaction(transaction)

                        Log.d("Banana", "${providerBinder?.isProviderInitialized}")

                        // providerBinder!!.addTransactionListener(this@HyperpayPlugin)
                    } catch (e: PaymentException) {
                        result.error(
                            "0.3",
                            e.localizedMessage,
                            ""
                        )
                    }
                }
            }
        } else {
            result.notImplemented()
        }
    }

    /**
     * This function checks the provided card params and return a PlatformException to Flutter if any are not valid.
     * */
    private fun checkCreditCardValid() {
        if (!CardPaymentParams.isNumberValid(cardNumber)) {
            error(
                "1.1",
                "Card number is not valid for brand ${brand.name}",
                ""
            )
        } else if (!CardPaymentParams.isHolderValid(cardHolder)) {
            error(
                "1.2",
                "Holder name is not valid",
                ""
            )
        } else if (!CardPaymentParams.isExpiryMonthValid(expiryMonth)) {
            error(
                "1.3",
                "Expiry month is not valid",
                ""
            )
        } else if (!CardPaymentParams.isExpiryYearValid(expiryYear)) {
            error(
                "1.4",
                "Expiry year is not valid",
                ""
            )
        } else if (!CardPaymentParams.isCvvValid(cvv)) {
            error(
                "1.5",
                "CVV is not valid",
                ""
            )
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun transactionCompleted(p0: Transaction?) {
        if (transaction == null) {
            return
        }

        try {
            if (transaction!!.transactionType == TransactionType.SYNC) {
                // Send request to your server to obtain transaction status
                success("Success: synchronous 🎉")
            } else {
                val uri = Uri.parse(transaction!!.redirectUrl)
                val intent = Intent(Intent.ACTION_VIEW, uri)
                startActivity(intent)
            }
        } catch (e: Exception) {
            // Display error
            error("Error ☹️")
        }
    }

    override fun transactionFailed(p0: Transaction?, p1: PaymentError?) {
        error(
            "${p1?.errorCode}",
            "Error ☹️ + ${p1?.errorMessage}",
            "${p1?.errorInfo}"
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.scheme.equals(mActivity.packageName)) {
            success("Success: asynchronous 🎉")
        }
    }

    // // Unbind service after quitting a transaction
    // override fun onStop() {
    //     super.onStop()
    //
    //     unbindService(serviceConnection)
    //     stopService(Intent(this, ConnectService::class.java))
    // }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        mActivity = binding.activity;
    }

    override fun onDetachedFromActivityForConfigChanges() {
        TODO("Not yet implemented")
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        TODO("Not yet implemented")
    }

    override fun onDetachedFromActivity() {
        TODO("Not yet implemented")
    }

    override fun brandsValidationRequestSucceeded(p0: BrandsValidation?) {
        TODO("Not yet implemented")
    }

    override fun brandsValidationRequestFailed(p0: PaymentError?) {
        TODO("Not yet implemented")
    }

    override fun imagesRequestSucceeded(p0: ImagesRequest?) {
        TODO("Not yet implemented")
    }

    override fun imagesRequestFailed() {
        TODO("Not yet implemented")
    }

    override fun paymentConfigRequestSucceeded(p0: CheckoutInfo?) {
        TODO("Not yet implemented")
    }

    override fun paymentConfigRequestFailed(p0: PaymentError?) {
        TODO("Not yet implemented")
    }
}
