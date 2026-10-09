package com.homestay.pms_backend.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class BookingSourceUpdateRequest {

    @Size(max = 100)
    private String name;

    private Boolean active;
}