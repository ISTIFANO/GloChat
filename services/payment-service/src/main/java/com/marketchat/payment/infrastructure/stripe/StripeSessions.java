package com.marketchat.payment.infrastructure.stripe;

import com.marketchat.payment.domain.port.PaymentProvider;
import com.stripe.model.checkout.Session;

final class StripeSessions {

    private StripeSessions() {
    }

    static PaymentProvider.SessionState state(Session s) {
        return new PaymentProvider.SessionState(s.getId(), s.getStatus(), s.getPaymentStatus(), s.getAmountTotal(),
                s.getCurrency(), s.getPaymentIntent());
    }
}
