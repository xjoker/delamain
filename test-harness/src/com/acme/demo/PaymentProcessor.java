package com.acme.demo;

/**
 * Public entry point (kept unobfuscated via keep rule) so we always have a
 * stable raw anchor to navigate from. References CreditCard and CryptoUtils
 * to create cross-reference targets, and holds an anonymous inner class.
 */
public class PaymentProcessor {

    private final CryptoUtils crypto = new CryptoUtils();

    // processPayment takes a custom type (CreditCard) parameter on purpose:
    // this is the Frida .overload(...) raw-vs-alias red-line probe.
    public String processPayment(CreditCard card, String authToken) {
        byte[] enc = crypto.deriveKey(authToken.getBytes());
        SecretKeeper keeper = new SecretKeeper();
        boolean ok = keeper.verify(card, enc);

        // Anonymous inner class -> becomes PaymentProcessor$1 (internal class).
        Runnable auditLog = new Runnable() {
            @Override
            public void run() {
                // Unique marker that lives ONLY inside an anonymous inner class.
                String hit = "ANON_AUDIT_MARKER_7Q2";
                System.out.println(hit + ok);
            }
        };
        auditLog.run();
        return ok ? "APPROVED" : "DECLINED";
    }
}
