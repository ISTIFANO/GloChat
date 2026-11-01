package com.marketchat.commerce.cart.domain.model;

import com.marketchat.commerce.shared.domain.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** One cart line: a product with its selected option values, a quantity and an optional note. */
public final class CartLine {

    public static final int MAX_NOTE_LENGTH = 200;

    private final UUID id;
    private final String productId;
    private final List<String> optionValueIds;   // sorted, no duplicates
    private String note;
    private int quantity;
    private String lineKey;

    private CartLine(UUID id, String productId, List<String> optionValueIds, String note, int quantity, String lineKey) {
        this.id = id;
        this.productId = productId;
        this.optionValueIds = optionValueIds;
        this.note = note;
        this.quantity = quantity;
        this.lineKey = lineKey;
    }

    static CartLine create(UUID id, String productId, List<String> optionValueIds, String note, int quantity) {
        List<String> sorted = optionValueIds.stream().distinct().sorted().toList();
        return new CartLine(id, productId, sorted, note, quantity, lineKey(productId, sorted, note));
    }

    public static CartLine restore(UUID id, String productId, List<String> optionValueIds, String note, int quantity) {
        return create(id, productId, optionValueIds, note, quantity);
    }

    /** Same product + same options + same note = same line (merged instead of duplicated). */
    static String lineKey(String productId, List<String> optionValueIds, String note) {
        String raw = productId + "|" + String.join(",", optionValueIds.stream().distinct().sorted().toList())
                + "|" + (note == null ? "" : note.toLowerCase());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Strips control characters and surrounding blanks; empty means no note. */
    static String cleanNote(String note) {
        if (note == null) {
            return null;
        }
        String clean = note.replaceAll("\\p{Cntrl}", " ").strip();
        if (clean.length() > MAX_NOTE_LENGTH) {
            throw new BusinessException("NOTE_TOO_LONG", 422, "A note is at most " + MAX_NOTE_LENGTH + " characters");
        }
        return clean.isEmpty() ? null : clean;
    }

    void changeQuantity(int quantity) {
        this.quantity = quantity;
    }

    void changeNote(String note, String lineKey) {
        this.note = note;
        this.lineKey = lineKey;
    }

    public UUID id() { return id; }
    public String productId() { return productId; }
    public List<String> optionValueIds() { return optionValueIds; }
    public String note() { return note; }
    public int quantity() { return quantity; }
    public String lineKey() { return lineKey; }
}
