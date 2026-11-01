package com.marketchat.commerce.orders.domain.model;

/** Who caused a status change (kept in the order's status history). */
public enum Actor { SYSTEM, MERCHANT, CUSTOMER, PAYMENT }
