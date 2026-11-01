package com.marketchat.commerce.cart.domain.port;

import com.marketchat.commerce.cart.domain.model.SavedAddress;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AddressRepository {

    List<SavedAddress> list(String customerId);

    Optional<SavedAddress> find(String customerId, UUID id);

    void add(SavedAddress address);

    boolean delete(String customerId, UUID id);
}
