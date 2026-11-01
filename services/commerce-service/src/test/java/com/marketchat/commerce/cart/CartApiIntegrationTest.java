package com.marketchat.commerce.cart;

import com.marketchat.commerce.support.ApiTestBase;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Inc 2 exit criteria (spec §12): cart rules, re-pricing, optimistic locking and per-user isolation. */
class CartApiIntegrationTest extends ApiTestBase {

    @Test
    void cartRequiresAToken() throws Exception {
        mvc.perform(get("/api/cart")).andExpect(status().isUnauthorized());
    }

    @Test
    void newCustomerHasAnEmptyCart() throws Exception {
        getAs(newCustomer(), "/api/cart")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", empty()))
                .andExpect(jsonPath("$.total").value(0.0))
                .andExpect(jsonPath("$.issues[*].code", hasItem("CART_EMPTY")));
    }

    @Test
    void addsAConfiguredProductPricedByTheCatalog() throws Exception {
        String me = newCustomer();
        // 65 + Large 12 + bacon 10 + fries 15 = 102
        ops(me, "[" + add("p-double-smash", 1, "p-double-smash-size-l", "p-ds-extra-bacon", "p-ds-side-fries") + "]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.merchant.id").value("m-smash"))
                .andExpect(jsonPath("$.merchant.name").value("Smash Lab Agdal"))
                .andExpect(jsonPath("$.lines[0].name").value("Double Smash Burger"))
                .andExpect(jsonPath("$.lines[0].imageUrl").value("/api/catalog/media/products/p-double-smash.webp"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(102.0))
                .andExpect(jsonPath("$.lines[0].selections[*].label", contains("Large", "Bacon de dinde", "Frites")))
                .andExpect(jsonPath("$.lines[0].selections[0].group").value("Taille"))
                .andExpect(jsonPath("$.subtotal").value(102.0))
                .andExpect(jsonPath("$.itemCount").value(1));
    }

    @Test
    void identicalLinesAreMerged() throws Exception {
        String me = newCustomer();
        ops(me, "[" + add("p-classic-cheese", 1, "p-classic-cheese-size-m") + "]");
        ops(me, "[" + add("p-classic-cheese", 2, "p-classic-cheese-size-m") + "]")
                .andExpect(jsonPath("$.lines", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].quantity").value(3))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(135.0))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void missingRequiredOptionIsRejectedWithTheGroupName() throws Exception {
        ops(newCustomer(), "[" + add("p-double-smash", 1, "p-ds-extra-bacon") + "]")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OPTION_REQUIRED"))
                .andExpect(jsonPath("$.violations[0].group").value("Taille"));
    }

    @Test
    void optionOfAnotherProductIsRejected() throws Exception {
        ops(newCustomer(), "[" + add("p-double-smash", 1, "p-double-smash-size-m", "p-classic-cheese-drink-coke") + "]")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OPTION_NOT_ALLOWED"));
    }

    @Test
    void unknownProductIsNotFound() throws Exception {
        ops(newCustomer(), "[" + add("p-does-not-exist", 1) + "]")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void oneMerchantPerCartAndClearPlusAddSwitchesMerchant() throws Exception {
        String me = newCustomer();
        ops(me, "[" + add("p-coke", 1) + "]");
        ops(me, "[" + add("p-california", 1) + "]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_MERCHANT_CONFLICT"))
                .andExpect(jsonPath("$.cartMerchantId").value("m-smash"))
                .andExpect(jsonPath("$.productMerchantId").value("m-sakura"));
        ops(me, "[{\"op\":\"CLEAR\"}," + add("p-california", 1) + "]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchant.id").value("m-sakura"))
                .andExpect(jsonPath("$.lines", hasSize(1)));
    }

    @Test
    void aBatchIsAllOrNothing() throws Exception {
        String me = newCustomer();
        ops(me, "[" + add("p-coke", 1) + "," + add("p-double-smash", 1) + "]")   // second op misses the size
                .andExpect(status().isUnprocessableContent());
        getAs(me, "/api/cart").andExpect(jsonPath("$.lines", empty())).andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void updateAndRemoveLines() throws Exception {
        String me = newCustomer();
        String lineId = read(ops(me, "[" + add("p-coke", 1) + "," + add("p-fries", 1) + "]"), "$.lines[0].lineId");
        ops(me, "[{\"op\":\"UPDATE\",\"lineId\":\"" + lineId + "\",\"quantity\":4,\"note\":\"bien frais\"}]")
                .andExpect(jsonPath("$.lines[0].quantity").value(4))
                .andExpect(jsonPath("$.lines[0].note").value("bien frais"))
                .andExpect(jsonPath("$.subtotal").value(58.0));
        ops(me, "[{\"op\":\"REMOVE\",\"lineId\":\"" + lineId + "\"}]")
                .andExpect(jsonPath("$.lines", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].productId").value("p-fries"));
    }

    @Test
    void staleExpectedVersionIsAConflict() throws Exception {
        String me = newCustomer();
        ops(me, "[" + add("p-coke", 1) + "]");                         // version 1
        ops(me, "[" + add("p-fries", 1) + "]");                        // version 2
        postAs(me, "/api/cart/operations", "{\"expectedVersion\":1,\"operations\":[" + add("p-coke", 1) + "]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentVersion").value(2));
        postAs(me, "/api/cart/operations", "{\"expectedVersion\":2,\"operations\":[" + add("p-coke", 1) + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void customersNeverSeeOrChangeEachOthersCart() throws Exception {
        String alice = newCustomer();
        String bob = newCustomer();
        String aliceLine = read(ops(alice, "[" + add("p-coke", 2) + "]"), "$.lines[0].lineId");
        getAs(bob, "/api/cart").andExpect(jsonPath("$.lines", empty()));
        ops(bob, "[{\"op\":\"REMOVE\",\"lineId\":\"" + aliceLine + "\"}]")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_LINE_NOT_FOUND"));
        getAs(alice, "/api/cart").andExpect(jsonPath("$.lines[0].quantity").value(2));
    }

    @Test
    void theCartIsRepricedWhenTheCatalogPriceChanges() throws Exception {
        String me = newCustomer();
        ops(me, "[" + add("p-fries", 2) + "]").andExpect(jsonPath("$.subtotal").value(36.0));
        try {
            setPrice("p-fries", "20.00");
            getAs(me, "/api/cart")
                    .andExpect(jsonPath("$.lines[0].unitPrice").value(20.0))
                    .andExpect(jsonPath("$.subtotal").value(40.0));
        } finally {
            setPrice("p-fries", "18.00");
        }
    }

    @Test
    void outOfStockProductBecomesAnUnavailableLine() throws Exception {
        String me = newCustomer();
        ops(me, "[" + add("p-coke", 1) + "," + add("p-fries", 3) + "]");
        try {
            jdbc.sql("UPDATE products SET in_stock = false WHERE id = 'p-coke'").update();
            getAs(me, "/api/cart")
                    .andExpect(jsonPath("$.lines[0].available").value(false))
                    .andExpect(jsonPath("$.lines[0].unavailableReason").value("OUT_OF_STOCK"))
                    .andExpect(jsonPath("$.subtotal").value(54.0))
                    .andExpect(jsonPath("$.issues[*].code", hasItem("ITEM_UNAVAILABLE")));
        } finally {
            jdbc.sql("UPDATE products SET in_stock = true WHERE id = 'p-coke'").update();
        }
    }

    @Test
    void deliveryTermsAndCheckoutIssuesAreComputed() throws Exception {
        String me = newCustomer();
        ops(me, "[" + add("p-coke", 1) + "]")
                .andExpect(jsonPath("$.issues[*].code", containsInAnyOrder(
                        "NO_DELIVERY_ADDRESS", "BELOW_MINIMUM_ORDER", "NO_PAYMENT_METHOD")))
                .andExpect(jsonPath("$.issues[?(@.code=='BELOW_MINIMUM_ORDER')].missing", contains(30.0)));

        putAs(me, "/api/cart/delivery", "{\"address\":" + CASABLANCA + "}")
                .andExpect(jsonPath("$.delivery.inZone").value(false))
                .andExpect(jsonPath("$.issues[*].code", hasItem("OUT_OF_DELIVERY_ZONE")));

        ops(me, "[" + add("p-fries", 2) + "]");
        putAs(me, "/api/cart/delivery", "{\"address\":" + AGDAL + "}")
                .andExpect(jsonPath("$.delivery.inZone").value(true))
                .andExpect(jsonPath("$.delivery.fee").value(11.0))
                .andExpect(jsonPath("$.delivery.etaText").value("13–23 min"))
                .andExpect(jsonPath("$.subtotal").value(46.0))
                .andExpect(jsonPath("$.total").value(57.0));
        putAs(me, "/api/cart/payment-method", "{\"method\":\"CASH\"}")
                .andExpect(jsonPath("$.paymentMethod").value("CASH"))
                .andExpect(jsonPath("$.acceptedPaymentMethods", containsInAnyOrder("CASH", "CARD")))
                .andExpect(jsonPath("$.issues", empty()));
    }

    @Test
    void closedMerchantBlocksCheckout() throws Exception {
        String me = newCustomer();
        readyCart(me, "CASH");
        clock.set("2026-11-05T04:00:00");   // Smash Lab closes at 02:00
        getAs(me, "/api/cart")
                .andExpect(jsonPath("$.merchant.open").value(false))
                .andExpect(jsonPath("$.issues[*].code", contains("MERCHANT_CLOSED")));
    }

    @Test
    void savedAddressesBelongToTheirOwner() throws Exception {
        String me = newCustomer();
        String other = newCustomer();
        String id = read(postAs(me, "/api/cart/addresses", AGDAL).andExpect(status().isCreated()), "$.id");
        getAs(me, "/api/cart/addresses").andExpect(jsonPath("$[*].label", contains("Maison")));
        getAs(other, "/api/cart/addresses").andExpect(jsonPath("$", empty()));

        putAs(other, "/api/cart/delivery", "{\"addressId\":\"" + id + "\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADDRESS_NOT_FOUND"));
        putAs(me, "/api/cart/delivery", "{\"addressId\":\"" + id + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.line").value("12 avenue de France"));

        deleteAs(other, "/api/cart/addresses/" + id).andExpect(status().isNotFound());
        deleteAs(me, "/api/cart/addresses/" + id).andExpect(status().isNoContent());
        getAs(me, "/api/cart/addresses").andExpect(jsonPath("$", empty()));
    }

    @Test
    void invalidAddressAndPaymentMethodAreRejected() throws Exception {
        String me = newCustomer();
        putAs(me, "/api/cart/delivery", "{\"address\":{\"label\":\"x\",\"line\":\"\",\"city\":\"Rabat\",\"lat\":34,\"lng\":-6.8}}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_ADDRESS"));
        putAs(me, "/api/cart/payment-method", "{\"method\":\"BITCOIN\"}")
                .andExpect(status().isBadRequest());
        getAs(me, "/api/cart").andExpect(jsonPath("$.paymentMethod").value(not("BITCOIN")));
    }
}
