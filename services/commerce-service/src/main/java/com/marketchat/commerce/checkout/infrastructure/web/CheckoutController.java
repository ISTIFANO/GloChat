package com.marketchat.commerce.checkout.infrastructure.web;

import com.marketchat.commerce.checkout.application.CheckoutService;
import com.marketchat.commerce.checkout.application.ConfirmResult;
import com.marketchat.commerce.checkout.application.QuoteView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Checkout API (spec §6). Confirm is called by the app when the user taps "Confirm & pay". */
@RestController
@RequestMapping("/api/checkout")
class CheckoutController {

    private final CheckoutService checkout;

    CheckoutController(CheckoutService checkout) {
        this.checkout = checkout;
    }

    record ConfirmRequest(@NotNull UUID quoteId, @NotBlank String confirmationToken, @NotNull UUID idempotencyKey) {
    }

    @PostMapping("/quote")
    QuoteView quote(@AuthenticationPrincipal Jwt jwt) {
        return checkout.createQuote(jwt.getSubject());
    }

    /**
     * 201 with the order (200 for an idempotent replay), or 409 PRICE_CHANGED with the new quote to confirm.
     * For a card order the body's paymentUrl is where the app sends the customer.
     */
    @PostMapping("/confirm")
    ResponseEntity<?> confirm(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ConfirmRequest r) {
        ConfirmResult result = checkout.confirm(jwt.getSubject(), r.quoteId(), r.confirmationToken(), r.idempotencyKey());
        if (result instanceof ConfirmResult.PriceChanged changed) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "Prices changed since the quote: please review and confirm the new total");
            pd.setProperty("code", "PRICE_CHANGED");
            pd.setProperty("quote", changed.newQuote());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
        }
        ConfirmResult.Placed placed = (ConfirmResult.Placed) result;
        return ResponseEntity.status(placed.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(placed.order());
    }
}
