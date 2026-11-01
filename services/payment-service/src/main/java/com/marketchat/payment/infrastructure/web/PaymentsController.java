package com.marketchat.payment.infrastructure.web;

import com.marketchat.payment.application.PaymentService;
import com.marketchat.payment.domain.model.PaymentException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Internal API used by commerce-service (client-credentials token with scope "internal", not routed by the gateway)
 * and the public Stripe webhook (no token: the Stripe signature is the authentication).
 */
@RestController
class PaymentsController {

    private final PaymentService payments;

    PaymentsController(PaymentService payments) {
        this.payments = payments;
    }

    record SessionRequest(@NotNull UUID orderId, @NotBlank String orderNumber, @NotNull BigDecimal amount,
                          @NotBlank String currency, @NotBlank String successUrl, @NotBlank String cancelUrl) {
    }

    record SyncRequest(@NotNull UUID orderId) {
    }

    @PostMapping("/internal/payments/sessions")
    PaymentService.SessionView createSession(@Valid @RequestBody SessionRequest r) {
        return payments.createSession(new PaymentService.CreateSession(r.orderId(), r.orderNumber(), r.amount(),
                r.currency(), r.successUrl(), r.cancelUrl()));
    }

    @PostMapping("/internal/payments/sync")
    PaymentService.SyncView sync(@Valid @RequestBody SyncRequest r) {
        return payments.sync(r.orderId());
    }

    /** Raw body: the signature is computed over the exact bytes Stripe sent. */
    @PostMapping("/api/payments/stripe/webhook")
    Map<String, String> stripeWebhook(@RequestBody(required = false) String payload,
                                      @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        return Map.of("result", payments.handleWebhook(payload == null ? "" : payload, signature));
    }

    @ExceptionHandler(PaymentException.class)
    ProblemDetail paymentError(PaymentException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.httpStatus()), e.getMessage());
        pd.setProperty("code", e.code());
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request");
        pd.setProperty("code", "INVALID_ARGUMENT");
        return pd;
    }
}
