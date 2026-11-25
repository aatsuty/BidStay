package com.bidstay.auth;

interface PhoneVerifier {
    String send(String phone);
    boolean verify(String verificationSid, String code);
}
