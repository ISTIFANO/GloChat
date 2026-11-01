package com.marketchat.commerce.support;

import com.jayway.jsonpath.JsonPath;
import com.marketchat.commerce.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Real PostgreSQL (Flyway + demo seed), real security filter chain with a JWT whose subject is the customer.
 * Each test uses fresh customer ids, so tests never see each other's carts or orders.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Import({TestcontainersConfiguration.class, CommerceTestConfig.class})
public abstract class ApiTestBase {

    /** Agdal, Rabat: inside Smash Lab's delivery zone. */
    public static final String AGDAL = """
            {"label":"Maison","line":"12 avenue de France","city":"Rabat","lat":33.9981,"lng":-6.8497}""";
    /** Casablanca: ~90 km away, outside every Rabat delivery zone. */
    public static final String CASABLANCA = """
            {"label":"Bureau","line":"1 boulevard Anfa","city":"Casablanca","lat":33.5731,"lng":-7.5898}""";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected CommerceTestConfig.MutableClock clock;

    @Autowired
    protected CommerceTestConfig.FakePaymentGateway paymentGateway;

    @BeforeEach
    void resetDoubles() {
        clock.set(CommerceTestConfig.OPEN_TIME);
        paymentGateway.reset();
    }

    protected static String newCustomer() {
        return "user-" + UUID.randomUUID();
    }

    protected static RequestPostProcessor as(String customer) {
        return jwt().jwt(j -> j.subject(customer).claim("email", customer + "@test.local"));
    }

    protected ResultActions getAs(String customer, String url) throws Exception {
        return mvc.perform(get(url).with(as(customer)));
    }

    protected ResultActions postAs(String customer, String url, String json) throws Exception {
        return mvc.perform(post(url).with(as(customer)).contentType(MediaType.APPLICATION_JSON).content(json == null ? "" : json));
    }

    protected ResultActions putAs(String customer, String url, String json) throws Exception {
        return mvc.perform(put(url).with(as(customer)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions deleteAs(String customer, String url) throws Exception {
        return mvc.perform(delete(url).with(as(customer)));
    }

    protected ResultActions ops(String customer, String operationsJson) throws Exception {
        return postAs(customer, "/api/cart/operations", "{\"operations\":" + operationsJson + "}");
    }

    protected static String add(String productId, int quantity, String... optionValueIds) {
        StringBuilder sb = new StringBuilder("{\"op\":\"ADD\",\"productId\":\"" + productId + "\",\"quantity\":" + quantity
                + ",\"optionValueIds\":[");
        for (int i = 0; i < optionValueIds.length; i++) {
            sb.append(i == 0 ? "" : ",").append('"').append(optionValueIds[i]).append('"');
        }
        return sb.append("]}").toString();
    }

    protected static <T> T read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
    }

    /** A cart that can be ordered: 2 x Double Smash (Normal) + fries = 2 x 80 = 160 MAD, delivered to Agdal. */
    protected void readyCart(String customer, String paymentMethod) throws Exception {
        ops(customer, "[" + add("p-double-smash", 2, "p-double-smash-size-m", "p-ds-side-fries") + "]");
        putAs(customer, "/api/cart/delivery", "{\"address\":" + AGDAL + "}");
        putAs(customer, "/api/cart/payment-method", "{\"method\":\"" + paymentMethod + "\"}");
    }

    protected void setPrice(String productId, String price) {
        jdbc.sql("UPDATE products SET base_price = CAST(:price AS numeric) WHERE id = :id")
                .param("price", price).param("id", productId).update();
    }
}
