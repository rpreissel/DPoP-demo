package com.example.dpop.ext_stammdaten;

public record PersonData(
        Long id,
        String kvnr,
        String name,
        String vorname
) {
}
