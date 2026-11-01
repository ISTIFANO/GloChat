package com.marketchat.commerce.cart.infrastructure.web;

import com.marketchat.commerce.cart.application.CartOperation;
import com.marketchat.commerce.cart.application.CartService;
import com.marketchat.commerce.cart.application.CartView;
import com.marketchat.commerce.cart.domain.model.DeliveryAddress;
import com.marketchat.commerce.cart.domain.model.PaymentMethod;
import com.marketchat.commerce.cart.domain.model.SavedAddress;
import com.marketchat.commerce.shared.domain.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Cart REST API (spec §9.1). The customer is always the token's subject: no customer id in the URL or body. */
@RestController
@RequestMapping("/api/cart")
class CartController {

    private final CartService carts;

    CartController(CartService carts) {
        this.carts = carts;
    }

    record OperationRequest(@NotNull String op, String productId, Integer quantity, List<String> optionValueIds,
                            String note, UUID lineId) {
    }

    record OperationsRequest(Long expectedVersion, @NotEmpty @Size(max = 20) List<@Valid OperationRequest> operations) {
    }

    record AddressRequest(String label, String line, String city, Double lat, Double lng) {
        DeliveryAddress toAddress() {
            if (lat == null || lng == null) {
                throw new BusinessException("INVALID_ADDRESS", 422, "Address coordinates are required");
            }
            return new DeliveryAddress(label, line, city, lat, lng);
        }
    }

    /** Either a saved address id or a full address. */
    record DeliveryRequest(UUID addressId, AddressRequest address) {
    }

    record PaymentMethodRequest(@NotNull PaymentMethod method) {
    }

    record AddressView(UUID id, String label, String line, String city, double lat, double lng) {
        static AddressView of(SavedAddress a) {
            return new AddressView(a.id(), a.address().label(), a.address().line(), a.address().city(),
                    a.address().lat(), a.address().lng());
        }
    }

    @GetMapping
    CartView view(@AuthenticationPrincipal Jwt jwt) {
        return carts.view(jwt.getSubject());
    }

    @PostMapping("/operations")
    CartView apply(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OperationsRequest request) {
        List<CartOperation> ops = request.operations().stream().map(CartController::toOperation).toList();
        return carts.apply(jwt.getSubject(), request.expectedVersion(), ops);
    }

    @PutMapping("/delivery")
    CartView delivery(@AuthenticationPrincipal Jwt jwt, @RequestBody DeliveryRequest request) {
        if (request.addressId() != null) {
            return carts.setDeliveryFromSaved(jwt.getSubject(), request.addressId());
        }
        if (request.address() == null) {
            throw new BusinessException("INVALID_ADDRESS", 422, "addressId or address is required");
        }
        return carts.setDelivery(jwt.getSubject(), request.address().toAddress());
    }

    @PutMapping("/payment-method")
    CartView paymentMethod(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PaymentMethodRequest request) {
        return carts.setPaymentMethod(jwt.getSubject(), request.method());
    }

    @GetMapping("/addresses")
    List<AddressView> addresses(@AuthenticationPrincipal Jwt jwt) {
        return carts.addresses(jwt.getSubject()).stream().map(AddressView::of).toList();
    }

    @PostMapping("/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    AddressView addAddress(@AuthenticationPrincipal Jwt jwt, @RequestBody AddressRequest request) {
        return AddressView.of(carts.addAddress(jwt.getSubject(), request.toAddress()));
    }

    @DeleteMapping("/addresses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteAddress(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        carts.deleteAddress(jwt.getSubject(), id);
    }

    private static CartOperation toOperation(OperationRequest r) {
        return switch (r.op().toUpperCase()) {
            case "ADD" -> {
                if (r.productId() == null) {
                    throw new BusinessException("INVALID_ARGUMENT", 400, "ADD needs a productId");
                }
                yield new CartOperation.Add(r.productId(), r.quantity() == null ? 1 : r.quantity(), r.optionValueIds(), r.note());
            }
            case "UPDATE" -> new CartOperation.Update(requireLine(r), r.quantity(), r.note());
            case "REMOVE" -> new CartOperation.Remove(requireLine(r));
            case "CLEAR" -> new CartOperation.Clear();
            default -> throw new BusinessException("INVALID_ARGUMENT", 400, "Unknown operation " + r.op());
        };
    }

    private static UUID requireLine(OperationRequest r) {
        if (r.lineId() == null) {
            throw new BusinessException("INVALID_ARGUMENT", 400, r.op() + " needs a lineId");
        }
        return r.lineId();
    }
}
