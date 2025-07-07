package com.dev.inapppaysdk.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;

import com.dev.inapppaysdk.R;
import com.dev.inapppaysdk.constants.InAppConstants;
import com.dev.inapppaysdk.logic.Validator;
import com.dev.inapppaysdk.logic.Watcher;
import com.dev.inapppaysdk.utils.PurchaseContextManager;
import com.google.android.material.textfield.TextInputLayout;

public class PurchaseDialogManager {

    public interface PurchaseDialogCallback {
        void onPurchaseRequested(String paymentMethod, String cardNumber, String expiry, String cvv, String name);

        void onPurchaseCancelled();
    }

    private Context context;
    private PurchaseDialogCallback dialogCallback;
    private PurchaseContextManager contextManager;

    // In your constructor, validate the context
    public PurchaseDialogManager(Context context, PurchaseDialogCallback dialogCallback) {
        // Ensure we have an Activity context
        if (!(context instanceof Activity)) {
            throw new IllegalArgumentException("Context must be an Activity context");
        }

        this.context = new ContextThemeWrapper(context, R.style.Theme_SDK_Dialog);
        this.dialogCallback = dialogCallback;
        this.contextManager = PurchaseContextManager.getInstance();
    }

    public void showOnetimeDialog(String title, String description) {
        showPurchaseDialog("One-Time Purchase", "Complete your one-time purchase");
    }

    public void showRepurchaseDialog(String title, String description) {
        showPurchaseDialog("Repurchase Item", "Purchase this item");
    }

    public void showSubscriptionDialog(String title, String description) {
        showPurchaseDialog("Subscription", "Subscribe");
    }

    public void showGeneralDialog(String title, String description) {
        showPurchaseDialog(title, description);
    }

    private void showPurchaseDialog(String title, String description) {
        // Validate context before showing dialog
        if (context == null) {
            Log.e("Dialog", "Context is null");
            return;
        }

        // Get the base context (Activity) from ContextThemeWrapper
        Context baseContext = ((ContextThemeWrapper) context).getBaseContext();
        if (baseContext instanceof Activity) {
            Activity activity = (Activity) baseContext;
            if (activity.isFinishing() || activity.isDestroyed()) {
                Log.e("Dialog", "Activity is finishing or destroyed");
                return;
            }
        }

        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = LayoutInflater.from(context).inflate(R.layout.sdk_popup, null);
        dialog.setContentView(view);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(false);
        dialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );

        setupViews(dialog, view, title);
        dialog.show();
    }

    private void setupViews(Dialog dialog, View view, String title) {
        ImageView btnClose = view.findViewById(R.id.btnClose);
        TextView amountText = view.findViewById(R.id.amountText);
        TextView titleText = view.findViewById(R.id.titleText);

        titleText.setText(title);

        // Display amount information
        String amount = contextManager.getAmount();
        String label = contextManager.getLabel();

        if (amount != null && label != null) {
            amountText.setText(label + ": " + "$ " + amount);
            amountText.setVisibility(View.VISIBLE);
        } else {
            amountText.setVisibility(View.GONE);
        }

        btnClose.setOnClickListener(v -> {
            dialog.dismiss();
            if (dialogCallback != null) {
                dialogCallback.onPurchaseCancelled();
            }
        });

        setupPaymentSteps(dialog, view);
    }

    private void setupPaymentSteps(Dialog dialog, View view) {
        // Step 1 - Payment method selection
        LinearLayout stepOneLayout = view.findViewById(R.id.stepOneLayout);
        Button btnCard = view.findViewById(R.id.btnCard);
        Button btnPaypal = view.findViewById(R.id.btnPaypal);

        // Step 2 - Payment details
        LinearLayout stepTwoLayout = view.findViewById(R.id.stepTwoLayout);
        TextView selectedMethodText = view.findViewById(R.id.selectedMethodText);
        Button btnSubmitCard = view.findViewById(R.id.btnSubmitCard);
        TextView titleText = view.findViewById(R.id.titleText);

        // Input fields
        TextInputLayout etCardNumber = view.findViewById(R.id.etCardNumber);
        TextInputLayout etExpiry = view.findViewById(R.id.etExpiry);
        TextInputLayout etCvv = view.findViewById(R.id.etCvv);
        TextInputLayout etName = view.findViewById(R.id.etName);

        // Setup validators
        Validator cardValidator = createCardValidator(etCardNumber);
        Validator expiryValidator = createExpiryValidator(etExpiry);
        Validator cvvValidator = createCvvValidator(etCvv);
        Validator nameValidator = createNameValidator(etName);

        btnPaypal.setOnClickListener(v -> {
            stepOneLayout.setVisibility(View.GONE);
            stepTwoLayout.setVisibility(View.VISIBLE);
            titleText.setText("Input details");
            selectedMethodText.setText("You selected: PayPal");

            hideCardInputs(etCardNumber, etExpiry, etCvv, etName);
            btnSubmitCard.setVisibility(View.GONE);

            // Process PayPal payment
            if (dialogCallback != null) {
                dialogCallback.onPurchaseRequested(InAppConstants.PAYMENT_METHOD_PAYPAL, null, null, null, null);
            }
            dialog.dismiss();
        });

        btnCard.setOnClickListener(v -> {
            stepOneLayout.setVisibility(View.GONE);
            stepTwoLayout.setVisibility(View.VISIBLE);
            selectedMethodText.setText("You selected: Card");
            titleText.setText("Input details");

            showCardInputs(etCardNumber, etExpiry, etCvv, etName);
            btnSubmitCard.setVisibility(View.VISIBLE);
        });

        btnSubmitCard.setOnClickListener(v -> {
            if (validateCardInputs(cardValidator, expiryValidator, cvvValidator, nameValidator)) {
                String cardNumber = etCardNumber.getEditText().getText().toString();
                String expiry = etExpiry.getEditText().getText().toString();
                String cvv = etCvv.getEditText().getText().toString();
                String name = etName.getEditText().getText().toString();

                // Process card payment
                if (dialogCallback != null) {
                    dialogCallback.onPurchaseRequested(InAppConstants.PAYMENT_METHOD_CARD, cardNumber, expiry, cvv, name);
                }

                dialog.dismiss();
            }
        });
    }

    private void hideCardInputs(TextInputLayout... inputs) {
        for (TextInputLayout input : inputs) {
            if (input != null) {
                input.setVisibility(View.GONE);
            }
        }
    }

    private void showCardInputs(TextInputLayout... inputs) {
        for (TextInputLayout input : inputs) {
            if (input != null) {
                input.setVisibility(View.VISIBLE);
            }
        }
    }

    private boolean validateCardInputs(Validator... validators) {
        boolean isValid = true;
        for (Validator validator : validators) {
            if (!validator.isValid()) {
                isValid = false;
            }
        }
        return isValid;
    }

    private Validator createCardValidator(TextInputLayout etCardNumber) {
        return Validator.Builder
                .with(etCardNumber)
                .addWatcher(new Watcher.NotBlank("Card number is required"))
                .addWatcher(new Watcher.PatternWatcher("Invalid card format", "\\d{16}"))
                .build();
    }

    private Validator createExpiryValidator(TextInputLayout etExpiry) {
        return Validator.Builder
                .with(etExpiry)
                .addWatcher(new Watcher.NotBlank("Expiry is required"))
                .addWatcher(new Watcher.DateFormatWatcher("Use MM/yy format", "MM/yy"))
                .build();
    }

    private Validator createCvvValidator(TextInputLayout etCvv) {
        return Validator.Builder
                .with(etCvv)
                .addWatcher(new Watcher.NotBlank("CVV is required"))
                .addWatcher(new Watcher.PatternWatcher("Invalid CVV", "\\d{3,4}"))
                .build();
    }

    private Validator createNameValidator(TextInputLayout etName) {
        return Validator.Builder
                .with(etName)
                .addWatcher(new Watcher.NotBlank("Name is required"))
                .addWatcher(new Watcher.PatternWatcher("Invalid name", "^[a-zA-Z ]+$"))
                .build();
    }
}