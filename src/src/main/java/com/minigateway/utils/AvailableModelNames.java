package com.minigateway.utils;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
@Data
public class AvailableModelNames {
    private Set<String> availableModelNames;

    public AvailableModelNames() {
        availableModelNames = new HashSet<>();
        availableModelNames.add("DeepSeek-v4-flash");
        availableModelNames.add("DeepSeek-v4-pro");
    }
}
