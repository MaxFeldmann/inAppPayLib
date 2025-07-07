package com.dev.inapppaysdk;

import android.content.Context;
import com.dev.inapppaysdk.api.ApiClient;
import com.dev.inapppaysdk.api.InAppApiService;
import com.dev.inapppaysdk.callbacks.*;
import com.dev.inapppaysdk.utils.DeviceUtils;
import com.dev.inapppaysdk.utils.PurchaseContextManager;
import com.dev.inapppaysdk.ui.PurchaseDialogManager;
import com.dev.inapppaysdk.interfaces.Popupable;
import com.dev.inapppaysdk.constants.InAppConstants;

import java.util.HashMap;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class InAppPaySDK implements Popupable, PurchaseDialogManager.PurchaseDialogCallback {

    private Context context;
    private String userId;
    private String projectName;
    private String userCountry;
    private InAppApiService apiService;
    private PurchaseContextManager contextManager;
    private PurchaseDialogManager dialogManager;

    private InAppPaySDK() {
        apiService = ApiClient.getApiService();
        contextManager = PurchaseContextManager.getInstance();
    }

    public InAppPaySDK(String projectName, Context context) {
        this.context = context;
        this.apiService = ApiClient.getApiService();
        this.contextManager = PurchaseContextManager.getInstance();
        this.dialogManager = new PurchaseDialogManager(context, this);
        this.projectName = projectName;
        this.userId = DeviceUtils.getAndroidId(context);
        this.userCountry = DeviceUtils.detectUserCountry(context);
    }

    // Remove currency methods since we're using USD by default
    public String getLabel() {
        return contextManager.getLabel();
    }

    public InAppPaySDK setLabel(String label) {
        contextManager.setLabel(label);
        return this;
    }

    public String getAmount() {
        return contextManager.getAmount();
    }

    public void setAmount(String amount) {
        contextManager.setAmount(amount);
    }

    // Get the user ID (Android ID)
    public String getUserId() {
        return userId;
    }

    // Main purchase function - calls Firebase Functions
    public void buy(String productId, PurchaseCallback callback) {
        if (userId == null || userId.isEmpty()) {
            callback.onError("Could not get device ID", "MISSING_DEVICE_ID");
            return;
        }

        if (projectName == null || projectName.isEmpty()) {
            callback.onError("Project name is required", "MISSING_PROJECT_NAME");
            return;
        }

        // Set purchase context in manager
        contextManager.setPurchaseContext(productId, callback);

        // Prepare request data for Firebase Function - matches validateItemForPurchase expected params
        Map<String, Object> requestData = new HashMap<>();
        requestData.put("projectName", projectName);
        requestData.put("productId", productId);
        requestData.put("userId", userId);

        // Make Retrofit call to Firebase Function
        Call<Map<String, Object>> call = apiService.validateItemForPurchase(requestData);
        call.enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    Map<String, Object> responseBody = response.body();
                    Boolean success = (Boolean) responseBody.get("success");

                    if (Boolean.TRUE.equals(success)) {
                        // Extract item data from Firebase Function response
                        Map<String, Object> itemData = (Map<String, Object>) responseBody.get("data");
                        String itemType = (String) itemData.get("type");

                        // Set item data in context manager
                        contextManager.setItemData(itemData, itemType);

                        // Show appropriate popup based on item type
                        switch (itemType) {
                            case InAppConstants.TYPE_ONETIME:
                                dialogManager.showOnetimeDialog((String) itemData.get("name"), (String) itemData.get("description"));
                                break;
                            case InAppConstants.TYPE_REPURCHASE:
                                dialogManager.showRepurchaseDialog((String) itemData.get("name"), (String) itemData.get("description"));
                                break;
                            case InAppConstants.TYPE_SUBSCRIPTION:
                                dialogManager.showSubscriptionDialog((String) itemData.get("name"), (String) itemData.get("description"));
                                break;
                            default:
                                callback.onError("Unknown item type: " + itemType, "INVALID_ITEM_TYPE");
                                contextManager.reset();
                                break;
                        }
                    } else {
                        String error = (String) responseBody.get("error");
                        String errorCode = (String) responseBody.get("errorCode");
                        callback.onError(error != null ? error : "Item validation failed",
                                errorCode != null ? errorCode : "VALIDATION_FAILED");
                        contextManager.reset();
                    }
                } else {
                    callback.onError("Server error: " + response.code(), "SERVER_ERROR");
                    contextManager.reset();
                }
            }

            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                callback.onError("Network error: " + t.getMessage(), "NETWORK_ERROR");
                contextManager.reset();
            }
        });
    }

    // Check if user purchased a one-time or repurchase item
    public void isUserPurchased(String productId, CheckCallback callback) {
        if (userId == null || userId.isEmpty()) {
            callback.onError("Could not get device ID", "MISSING_DEVICE_ID");
            return;
        }

        if (projectName == null || projectName.isEmpty()) {
            callback.onError("Project name is required", "MISSING_PROJECT_NAME");
            return;
        }

        // Prepare request data matching checkUserPurchased expected params
        Map<String, Object> requestData = new HashMap<>();
        requestData.put("projectName", projectName);
        requestData.put("productId", productId);
        requestData.put("userId", userId);

        Call<Map<String, Object>> call = apiService.checkUserPurchased(requestData);
        call.enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    Map<String, Object> responseBody = response.body();
                    Boolean success = (Boolean) responseBody.get("success");

                    if (Boolean.TRUE.equals(success)) {
                        Map<String, Object> responseData = (Map<String, Object>) responseBody.get("data");
                        Boolean purchased = (Boolean) responseData.get("purchased");
                        Map<String, Object> purchaseData = (Map<String, Object>) responseData.get("purchaseData");
                        callback.onResult(Boolean.TRUE.equals(purchased), purchaseData);
                    } else {
                        String error = (String) responseBody.get("message"); // Cloud function uses "message" for error
                        String errorCode = "CHECK_FAILED"; // Cloud function doesn't return errorCode for this
                        callback.onError(error != null ? error : "Failed to check purchase status", errorCode);
                    }
                } else {
                    callback.onError("Server error: " + response.code(), "SERVER_ERROR");
                }
            }

            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                callback.onError("Network error: " + t.getMessage(), "NETWORK_ERROR");
            }
        });
    }

    // Check if user has active subscription
    public void isUserSubscribed(String productId, CheckCallback callback) {
        if (userId == null || userId.isEmpty()) {
            callback.onError("Could not get device ID", "MISSING_DEVICE_ID");
            return;
        }

        if (projectName == null || projectName.isEmpty()) {
            callback.onError("Project name is required", "MISSING_PROJECT_NAME");
            return;
        }

        // Prepare request data matching checkUserSubscribed expected params
        Map<String, Object> requestData = new HashMap<>();
        requestData.put("projectName", projectName);
        requestData.put("productId", productId);
        requestData.put("userId", userId);

        Call<Map<String, Object>> call = apiService.checkUserSubscribed(requestData);
        call.enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    Map<String, Object> responseBody = response.body();
                    Boolean success = (Boolean) responseBody.get("success");

                    if (Boolean.TRUE.equals(success)) {
                        Map<String, Object> responseData = (Map<String, Object>) responseBody.get("data");
                        Boolean subscribed = (Boolean) responseData.get("subscribed");
                        Map<String, Object> subscriptionData = (Map<String, Object>) responseData.get("subscriptionData");
                        callback.onResult(Boolean.TRUE.equals(subscribed), subscriptionData);
                    } else {
                        String error = (String) responseBody.get("message"); // Cloud function uses "message" for error
                        String errorCode = "CHECK_FAILED"; // Cloud function doesn't return errorCode for this
                        callback.onError(error != null ? error : "Failed to check subscription status", errorCode);
                    }
                } else {
                    callback.onError("Server error: " + response.code(), "SERVER_ERROR");
                }
            }

            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                callback.onError("Network error: " + t.getMessage(), "NETWORK_ERROR");
            }
        });
    }

    // Get user's subscription history
    public void getUserSubscriptions(PurchasesCallback callback) {
        if (userId == null || userId.isEmpty()) {
            callback.onError("Could not get device ID", "MISSING_DEVICE_ID");
            return;
        }

        if (projectName == null || projectName.isEmpty()) {
            callback.onError("Project name is required", "MISSING_PROJECT_NAME");
            return;
        }

        // Prepare request data matching getSubscriptions expected params
        Map<String, Object> requestData = new HashMap<>();
        requestData.put("projectName", projectName);
        requestData.put("userId", userId); // This will filter subscriptions for this user

        Call<Map<String, Object>> call = apiService.getSubscriptions(requestData);
        call.enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    Map<String, Object> responseBody = response.body();
                    Boolean success = (Boolean) responseBody.get("success");

                    if (Boolean.TRUE.equals(success)) {
                        Object subscriptionsData = responseBody.get("data");
                        callback.onSuccess(subscriptionsData);
                    } else {
                        String error = (String) responseBody.get("message"); // Cloud function uses "message"
                        callback.onError(error != null ? error : "Failed to get subscriptions", "GET_SUBSCRIPTIONS_FAILED");
                    }
                } else {
                    callback.onError("Server error: " + response.code(), "SERVER_ERROR");
                }
            }

            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                callback.onError("Network error: " + t.getMessage(), "NETWORK_ERROR");
            }
        });
    }

    // Popup methods of PurchaseDialogManager
//    public void popupOnetime() {
//        dialogManager.showOnetimeDialog();
//    }
//
//    public void popupRepurchase() {
//        dialogManager.showRepurchaseDialog();
//    }
//
//    public void popupSubscription() {
//        dialogManager.showSubscriptionDialog((String) itemData.get(""), itemData.get(""));
//    }

    // PurchaseDialogManager.PurchaseDialogCallback implementation
    @Override
    public void onPurchaseRequested(String paymentMethod, String cardNumber, String expiry, String cvv, String name) {
        processPurchase(paymentMethod, cardNumber, expiry, cvv, name);
    }

    @Override
    public void onPurchaseCancelled() {
        PurchaseCallback callback = contextManager.getCurrentPurchaseCallback();
        if (callback != null) {
            callback.onError("Purchase cancelled by user", "USER_CANCELLED");
        }
        contextManager.reset();
    }

    private void processPurchase(String paymentMethod, String cardNumber, String expiry, String cvv, String name) {
        if (!contextManager.isValidContext()) {
            PurchaseCallback callback = contextManager.getCurrentPurchaseCallback();
            if (callback != null) {
                callback.onError("Invalid purchase context", "INVALID_CONTEXT");
            }
            return;
        }

        // Prepare purchase data for Firebase Function matching the expected schema
        Map<String, Object> purchaseData = new HashMap<>();
        purchaseData.put("projectName", projectName);  // Cloud function expects this
        purchaseData.put("userId", userId);
        purchaseData.put("productId", contextManager.getCurrentItemKey());  // Changed from itemKey to productId
        purchaseData.put("paymentMethod", paymentMethod);

        // Add payment details based on method
        if (InAppConstants.PAYMENT_METHOD_CARD.equals(paymentMethod)) {
            Map<String, Object> cardData = new HashMap<>();
            cardData.put("cardNumber", cardNumber);
            cardData.put("expiry", expiry);
            cardData.put("cvv", cvv);
            cardData.put("name", name);
            // Detect card type based on card number
            cardData.put("cardType", detectCardType(cardNumber));
            purchaseData.put("cardData", cardData);
        } else if (InAppConstants.PAYMENT_METHOD_PAYPAL.equals(paymentMethod)) {
            // Add PayPal data if needed
            Map<String, Object> paypalData = new HashMap<>();
            // Add PayPal specific fields here if you have them
            purchaseData.put("paypalData", paypalData);
        }

        // Make Retrofit call to Firebase Function
        Call<Map<String, Object>> call = apiService.processPurchase(purchaseData);
        call.enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> response) {
                PurchaseCallback callback = contextManager.getCurrentPurchaseCallback();

                if (response.isSuccessful() && response.body() != null) {
                    Map<String, Object> responseBody = response.body();
                    Boolean success = (Boolean) responseBody.get("success");

                    if (Boolean.TRUE.equals(success)) {
                        String message = (String) responseBody.get("message");
                        Map<String, Object> responseData = (Map<String, Object>) responseBody.get("data");

                        if (callback != null) {
                            callback.onSuccess(
                                    message != null ? message : "Purchase completed successfully",
                                    responseData
                            );
                        }
                    } else {
                        String error = (String) responseBody.get("error");
                        String errorCode = (String) responseBody.get("errorCode");

                        if (callback != null) {
                            callback.onError(
                                    error != null ? error : "Purchase failed",
                                    errorCode != null ? errorCode : "PURCHASE_FAILED"
                            );
                        }
                    }
                } else {
                    if (callback != null) {
                        callback.onError("Server error: " + response.code(), "SERVER_ERROR");
                    }
                }
                contextManager.reset();
            }

            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                PurchaseCallback callback = contextManager.getCurrentPurchaseCallback();
                if (callback != null) {
                    callback.onError("Network error: " + t.getMessage(), "NETWORK_ERROR");
                }
                contextManager.reset();
            }
        });
    }

    // Helper method to detect card type
    private String detectCardType(String cardNumber) {
        if (cardNumber == null || cardNumber.isEmpty()) {
            return "unknown";
        }

        String cleanNumber = cardNumber.replaceAll("\\s+", "");

        if (cleanNumber.startsWith("4")) {
            return "visa";
        } else if (cleanNumber.startsWith("5") || cleanNumber.startsWith("2")) {
            return "mastercard";
        } else if (cleanNumber.startsWith("3")) {
            return "amex";
        } else if (cleanNumber.startsWith("6")) {
            return "discover";
        }

        return "unknown";
    }

    public void show() {
        dialogManager.showGeneralDialog("Payment", "Complete your payment");
    }

    @Override
    public void popUp(Context context) {
        this.context = context;
        show();
    }
}