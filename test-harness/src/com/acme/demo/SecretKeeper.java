package com.acme.demo;

/**
 * The method name unlockVaultXYZ789 and the string VAULT_INNER_MARKER_5K1
 * exist ONLY inside the inner class VaultEntry. A correct metadata search
 * must find them; the internal-class-exclusion bug (HIGH #1) will miss them.
 */
public class SecretKeeper {

    boolean verify(CreditCard card, byte[] key) {
        VaultEntry entry = new VaultEntry();
        return entry.unlockVaultXYZ789(card) && key.length > 0;
    }

    /** Inner class -> SecretKeeper$VaultEntry (internal class). */
    static class VaultEntry {
        boolean unlockVaultXYZ789(CreditCard card) {
            String innerOnly = "VAULT_INNER_MARKER_5K1";
            return card != null && innerOnly.length() > 0;
        }
    }
}
