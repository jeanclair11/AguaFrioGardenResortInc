package com.aguafriogarden.resortinc.network;

/** Response body for POST /api/guest/password/forgot: the token identifying this recovery attempt. */
public class ForgotPasswordResponse {
    public String message;
    public String reset_token;
}
