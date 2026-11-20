package com.bidstay.partner;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "partner_messages")
@Getter @Setter @NoArgsConstructor
public class PartnerMessage {
    public enum Sender { BOT, HOTEL }
    public enum Kind { REQUEST, SYSTEM, REPLY }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long hotelId;
    @Enumerated(EnumType.STRING)
    private Sender sender;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    private String body;
    private Long bidOfferId;
    private Instant createdAt;
}
