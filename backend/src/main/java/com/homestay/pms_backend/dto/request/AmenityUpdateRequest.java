package com.homestay.pms_backend.dto.request;

import com.homestay.pms_backend.enums.AmenityCategory;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AmenityUpdateRequest {

    @Size(max = 100)
    private String name;

    private AmenityCategory category;

    @Size(max = 2000)
    private String description;
}