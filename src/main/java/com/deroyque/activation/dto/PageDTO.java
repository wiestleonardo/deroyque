package com.deroyque.activation.dto;

import java.util.List;

public record PageDTO<T>(

        List<T> content,

        Integer page,

        Integer size,

        Long totalElements,

        Integer totalPages) {

}
