package io.github.luma.client.specialthanks;

import java.util.List;
import java.util.Objects;

record SpecialThanksCatalog(int schema, List<SpecialThanksEntry> people) {

    SpecialThanksCatalog {
        people = people == null ? List.of() : people.stream()
                .filter(Objects::nonNull)
                .filter(SpecialThanksEntry::visible)
                .toList();
    }
}
