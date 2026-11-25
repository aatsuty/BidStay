package com.bidstay.auth;

import com.bidstay.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.util.Map;

@Component
class TwilioPhoneVerifier implements PhoneVerifier {
    private final RestClient client;
    private final String serviceSid;
    private final boolean configured;

    TwilioPhoneVerifier(RestClient.Builder builder,
                        @Value("${bidstay.auth.twilio-account-sid:}") String accountSid,
                        @Value("${bidstay.auth.twilio-auth-token:}") String authToken,
                        @Value("${bidstay.auth.twilio-verify-service-sid:}") String serviceSid) {
        this.serviceSid = serviceSid;
        configured = !accountSid.isBlank() && !authToken.isBlank() && !serviceSid.isBlank();
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(4));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        client = builder.baseUrl("https://verify.twilio.com/v2")
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> headers.setBasicAuth(accountSid, authToken))
                .build();
    }

    @Override
    public String send(String phone) {
        Result result = post("/Verifications", Map.of("To", "+91" + phone, "Channel", "sms"));
        if (result == null || result.sid() == null) throw unavailable();
        return result.sid();
    }

    @Override
    public boolean verify(String verificationSid, String code) {
        try {
            Result result = post("/VerificationCheck", Map.of("VerificationSid", verificationSid, "Code", code));
            return result != null && "approved".equals(result.status());
        } catch (HttpClientErrorException.NotFound ignored) {
            return false;
        }
    }

    private Result post(String operation, Map<String, String> fields) {
        if (!configured) throw unavailable();
        var form = new LinkedMultiValueMap<String, String>();
        fields.forEach(form::add);
        try {
            return client.post().uri("/Services/{serviceSid}" + operation, serviceSid)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve().body(Result.class);
        } catch (HttpClientErrorException.NotFound error) {
            if ("/VerificationCheck".equals(operation)) throw error;
            throw unavailable();
        } catch (HttpClientErrorException.TooManyRequests error) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many verification attempts. Try again later.");
        } catch (RestClientException error) {
            throw unavailable();
        }
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Phone verification is unavailable. Try again later.");
    }

    private record Result(String sid, String status) {}
}
