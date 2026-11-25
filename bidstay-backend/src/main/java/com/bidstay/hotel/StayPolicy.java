package com.bidstay.hotel;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

public record StayPolicy(@NotNull @JsonFormat(pattern = "HH:mm") LocalTime checkInFrom,
                         @NotNull @JsonFormat(pattern = "HH:mm") LocalTime checkOutBy,
                         @Size(max = 1000) String guestInstructions) {}
