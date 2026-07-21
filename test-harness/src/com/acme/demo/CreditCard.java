package com.acme.demo;

/** Custom type used as a method parameter (Frida overload probe target). */
public class CreditCard {
    String cardNumber;
    int cvv;

    public CreditCard(String cardNumber, int cvv) {
        this.cardNumber = cardNumber;
        this.cvv = cvv;
    }

    /** Static nested class -> CreditCard$Validator (internal class). */
    static class Validator {
        boolean luhnValid(String pan) {
            return pan != null && pan.length() >= 12;
        }
    }

    /** Non-static inner class -> CreditCard$Metadata (internal class). */
    class Metadata {
        String issuer() {
            return "ACME-BANK";
        }
    }
}
