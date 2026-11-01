package com.marketchat.commerce.shared.application;

import java.util.function.Supplier;

/** Port: runs a unit of work in one database transaction (keeps use cases free of Spring annotations). */
public interface Transactions {

    <T> T inTransaction(Supplier<T> work);
}
